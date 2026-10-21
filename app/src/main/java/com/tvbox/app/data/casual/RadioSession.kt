package com.tvbox.app.data.casual

import android.content.Context
import androidx.compose.runtime.Immutable
import com.tvbox.app.core.Result
import com.tvbox.app.core.TvLog
import com.tvbox.app.data.AppImages
import com.tvbox.app.data.MediaUrl
import com.tvbox.app.player.PlayerBootstrap
import com.tvbox.app.player.PlayerController
import com.tvbox.app.player.PlayerControllerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class RadioUiState(
    val loading: Boolean = true,
    val initialized: Boolean = false,
    val station: RadioStation? = null,
    val index: Int = -1,
    val total: Int = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val source: String = "",
)

/**
 * 电台会话语义（docs/CASUAL_SPEC.md §2.1）：
 * - 进入即播上次电台（持久化），无则播内置精选第一台；
 * - 断流 600ms → 试备用 URL → 再失败切下一台，不弹错；
 * - 切换 App 内页面继续出声（播放器实例常驻，页面离开只解绑 UI）。
 *
 * 说明：前台服务/通知栏需要 Manifest 注册 Service（t12 目录外，已向 captain 申请）；
 * 当前实现保证 App 内跨页面持续播放，进程被回收才停止。
 */
class RadioSession(
    context: Context,
    private val repository: CasualRepository,
) {
    private val appContext = context.applicationContext
    private val controller: PlayerControllerImpl = PlayerBootstrap.createAndRegister(appContext)
    private val seed = CasualRadioSeed(appContext)
    private val prefs = appContext.getSharedPreferences("tvbox_casual", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = _state

    private var stations: List<RadioStation> = emptyList()
    private var index: Int = -1
    private var urlIndex: Int = 0
    private var started = false
    private var hasPlayed = false
    private var active = true
    private var failoverJob: Job? = null
    private var bufferingWatch: Job? = null
    private var startupWatch: Job? = null

    init {
        scope.launch {
            controller.state.collect { playerState ->
                _state.update {
                    it.copy(
                        playing = playerState is PlayerController.State.Playing,
                        buffering = playerState is PlayerController.State.Buffering,
                        error = (playerState as? PlayerController.State.Error)?.message,
                    )
                }
                if (!active) return@collect
                when (playerState) {
                    is PlayerController.State.Playing -> {
                        if (!hasPlayed) TvLog.i("RadioSession", "state -> Playing (station=${_state.value.station?.name})")
                        hasPlayed = true
                        bufferingWatch?.cancel()
                        startupWatch?.cancel()
                        failoverJob?.cancel()
                        _state.update { it.copy(error = null, message = null) }
                    }
                    is PlayerController.State.Buffering -> {
                        if (hasPlayed) startBufferingWatch()
                    }
                    is PlayerController.State.Error -> {
                        TvLog.w("RadioSession", "state -> Error: ${(playerState as? PlayerController.State.Error)?.message}")
                        failover("播放失败，正在换台")
                    }
                    is PlayerController.State.Ended -> failover("流结束，正在换台")
                    else -> Unit
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        // 先用内置精选立即出声（<3s），在线列表在后台合并，不打断当前播放
        scope.launch {
            _state.update { it.copy(loading = true) }
            val seedStations = seed.load()
            stations = seedStations
            if (stations.isNotEmpty()) {
                val lastId = prefs.getString(KEY_LAST_STATION, null)
                index = stations.indexOfFirst { it.id == lastId }.takeIf { it >= 0 } ?: 0
                _state.update {
                    it.copy(
                        loading = false,
                        initialized = true,
                        total = stations.size,
                        source = "内置精选",
                    )
                }
                playCurrent(source = "恢复上次电台")
            }
            val online = when (val result = repository.radios(1)) {
                is Result.Ok -> result.value
                is Result.Err -> emptyList()
            }
            if (online.isNotEmpty()) {
                val currentId = stations.getOrNull(index)?.id
                stations = (stations + online).distinctBy { it.id }
                if (currentId != null) {
                    index = stations.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: index
                }
                _state.update {
                    it.copy(
                        loading = false,
                        total = stations.size,
                        source = "Radio Browser + 内置精选",
                    )
                }
            } else if (stations.isEmpty()) {
                _state.update { it.copy(loading = false, initialized = true, error = "暂无可用电台") }
            }
        }
    }

    /** 离开听页 / 其它播放器接管时：停止自动换台逻辑（音频按需继续）。 */
    fun setActive(value: Boolean) {
        active = value
        if (!value) bufferingWatch?.cancel()
    }

    /** 视频/直播开始前调用：主动让出播放器，避免音频与视频抢 codec。 */
    fun stopForExternal() {
        active = false
        bufferingWatch?.cancel()
        failoverJob?.cancel()
        runCatching { controller.pause() }
    }

    fun togglePause() {
        when (controller.state.value) {
            is PlayerController.State.Playing -> controller.pause()
            is PlayerController.State.Paused -> controller.resume()
            is PlayerController.State.Error -> playCurrent(source = "重试")
            else -> controller.resume()
        }
    }

    fun next() = step(1)
    fun previous() = step(-1)

    fun playIndex(target: Int) {
        if (stations.isEmpty()) return
        index = target.coerceIn(0, stations.lastIndex)
        urlIndex = 0
        playCurrent(source = "手动选台")
    }

    fun currentStations(): List<RadioStation> = stations

    fun releaseUi() {
        // 页面离开不停止播放：只取消 UI 相关的看门狗，音频由常驻播放器继续。
        bufferingWatch?.cancel()
    }

    private fun step(delta: Int) {
        if (stations.isEmpty()) return
        index = (index + delta + stations.size) % stations.size
        urlIndex = 0
        playCurrent(source = if (delta > 0) "下一台" else "上一台")
    }

    private fun playCurrent(source: String) {
        val station = stations.getOrNull(index) ?: return
        val url = station.urls.getOrNull(urlIndex) ?: return
        hasPlayed = false
        prefs.edit().putString(KEY_LAST_STATION, station.id).apply()
        _state.update {
            it.copy(
                station = station,
                index = index,
                total = stations.size,
                message = source,
                error = null,
            )
        }
        // 电台多为 MP3/AAC 直链：不强制 liveMode，否则 PlayerController 会强制按 HLS 解析导致 manifest malformed。
        controller.setLiveMode(false)
        startupWatch?.cancel()
        startupWatch = scope.launch {
            delay(STARTUP_TIMEOUT_MS)
            if (controller.state.value !is PlayerController.State.Playing) {
                failover("起播超时，正在换台")
            }
        }
        controller.play(
            MediaUrl(
                label = station.name,
                url = resolveUrl(url),
                headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Icy-MetaData" to "1",
                ),
            ),
            0L,
        )
        TvLog.i("RadioSession", "play ${station.name} url=$url source=$source")
    }

    /** HTTP 电台在 targetSdk 34 + cleartext 已允许；部分 https 台缺 ISRG 根，统一用 App 的宽松 client 域名即可。 */
    private fun resolveUrl(url: String): String = url

    private fun startBufferingWatch() {
        if (bufferingWatch?.isActive == true) return
        bufferingWatch = scope.launch {
            delay(STALL_MS)
            if (controller.state.value is PlayerController.State.Buffering) {
                failover("断流，正在切换")
            }
        }
    }

    private fun failover(reason: String) {
        if (failoverJob?.isActive == true) return
        failoverJob = scope.launch {
            delay(180)
            val station = stations.getOrNull(index)
            if (station == null) return@launch
            if (urlIndex + 1 < station.urls.size) {
                urlIndex += 1
                _state.update { it.copy(message = "$reason（备用线路）") }
                playCurrent(source = "备用线路")
            } else {
                index = if (stations.isEmpty()) 0 else (index + 1) % stations.size
                urlIndex = 0
                _state.update { it.copy(message = reason) }
                playCurrent(source = "自动换台")
            }
            bufferingWatch?.cancel()
        }
    }

    private companion object {
        const val KEY_LAST_STATION = "last_station_id"
        const val STALL_MS = 600L
        const val STARTUP_TIMEOUT_MS = 6_000L
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }
}
