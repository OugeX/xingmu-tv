package com.tvbox.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tvbox.desktop.core.Log
import com.tvbox.desktop.data.AppSettings
import com.tvbox.desktop.data.Http
import com.tvbox.desktop.data.PlaybackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.State as VlcState
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.awt.Canvas

/**
 * libVLC 播放控制器（桌面端唯一播放器实例）。
 *
 * - 视频输出：AWT Canvas（Compose SwingPanel 中嵌入，vlcj ComponentVideoSurface）
 * - 失败/黑屏判定：轮询 state/time/videoOutputs，按 [PlaybackLadder] 自动换线路/软解/降清晰度
 * - 所有 Compose 状态只在 Main 线程更新
 */
class PlayerController(private val scope: CoroutineScope) {

    class State {
        var active by mutableStateOf(false)
        var title by mutableStateOf("")
        var subtitle by mutableStateOf("")
        var isLive by mutableStateOf(false)
        var playing by mutableStateOf(false)
        var paused by mutableStateOf(false)
        var buffering by mutableStateOf(false)
        var timeMs by mutableStateOf(0L)
        var lengthMs by mutableStateOf(0L)
        var videoWidth by mutableStateOf(0)
        var videoHeight by mutableStateOf(0)
        var volume by mutableStateOf(80)
        var rate by mutableStateOf(1f)
        var attemptLabel by mutableStateOf("")
        var attempts by mutableStateOf<List<String>>(emptyList())
        var attemptIndex by mutableStateOf(0)
        var softwareDecode by mutableStateOf(false)
        var usedFallback by mutableStateOf(false)
        var notice by mutableStateOf<String?>(null)
        var error by mutableStateOf<String?>(null)
        var statusText by mutableStateOf("")
        var vlcVersion by mutableStateOf<String?>(null)
        var hasSurface by mutableStateOf(false)

        fun resetFor(request: PlaybackRequest) {
            active = true
            title = request.title
            subtitle = request.subtitle
            isLive = request.isLive
            playing = false
            paused = false
            buffering = true
            timeMs = 0
            lengthMs = 0
            videoWidth = 0
            videoHeight = 0
            rate = 1f
            attemptLabel = ""
            attempts = emptyList()
            attemptIndex = 0
            softwareDecode = false
            usedFallback = false
            notice = null
            error = null
            statusText = "准备播放…"
        }
    }

    val state = State()

    private var factory: MediaPlayerFactory? = null
    private var player: EmbeddedMediaPlayer? = null
    private var runtime: VlcLocator.Runtime? = null
    private var canvas: Canvas? = null
    private var boundCanvas: Canvas? = null
    private var bindRetries = 0

    private var pendingRequest: PlaybackRequest? = null
    private var currentRequest: PlaybackRequest? = null
    private var settingsSnapshot = AppSettings()
    private var attempts: List<PlaybackLadder.Attempt> = emptyList()
    private var attemptIndex = 0
    private var attemptFinished = false
    private var generation = 0
    private var watchJob: Job? = null

    fun prepare(runtime: VlcLocator.Runtime, settings: AppSettings): String? {
        releaseInternal()
        this.runtime = runtime
        settingsSnapshot = settings
        return try {
            VlcLocator.configure(runtime)
            val args = buildList {
                add("--no-video-title-show")
                add("--no-metadata-network-access")
                if (!settings.hardwareDecode) add("--avcodec-hw=none")
            }
            val created = MediaPlayerFactory(*args.toTypedArray())
            val version = runCatching { created.application().version() }.getOrNull()
            val createdPlayer = created.mediaPlayers().newEmbeddedMediaPlayer()
            createdPlayer.audio().setVolume(settings.volume)
            factory = created
            player = createdPlayer
            state.vlcVersion = version
            state.volume = settings.volume
            canvas?.let { bindSurface(it) }
            Log.i("Player", "libVLC ready version=$version runtime=${runtime.displayPath}")
            null
        } catch (t: Throwable) {
            Log.e("Player", "libVLC 初始化失败", t)
            state.error = "libVLC 初始化失败：${t.message ?: t.javaClass.simpleName}"
            "libVLC 初始化失败：${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun attachCanvas(newCanvas: Canvas) {
        canvas = newCanvas
        if (boundCanvas !== newCanvas) {
            boundCanvas = null
            bindRetries = 0
            state.hasSurface = false
        }
        bindSurface(newCanvas)
    }

    /**
     * Compose 的 SwingPanel 离开界面时调用，清掉旧的 Canvas 引用，
     * 避免下次播放误以为视频输出已就绪（旧 canvas 已不可显示）。
     */
    fun detachCanvas(canvas: Canvas? = null) {
        if (canvas == null || canvas === this.canvas) {
            this.canvas = null
            boundCanvas = null
            bindRetries = 0
            state.hasSurface = false
        }
    }

    private fun surfaceReady(): Boolean {
        val target = boundCanvas ?: return false
        return target.isDisplayable && target.isShowing && target.width > 0 && target.height > 0
    }

    private fun bindSurface(target: Canvas) {
        val f = factory ?: return
        val p = player ?: return
        if (boundCanvas === target) return
        if (!target.isDisplayable || target.width <= 0 || target.height <= 0) {
            if (bindRetries++ > 60) {
                state.error = "视频输出组件未能初始化（Canvas 尺寸 ${target.width}x${target.height}）"
                return
            }
            scope.launch {
                delay(150)
                bindSurface(target)
            }
            return
        }
        try {
            val surface = f.videoSurfaces().newVideoSurface(target)
            p.videoSurface().set(surface)
            boundCanvas = target
            state.hasSurface = true
            Log.i("Player", "video surface attached: ${target.width}x${target.height}")
            scope.launch { startPendingIfReady() }
        } catch (t: Throwable) {
            Log.e("Player", "绑定视频输出失败", t)
            state.error = "绑定视频输出失败：${t.message}"
        }
    }

    fun play(request: PlaybackRequest, settings: AppSettings) {
        if (factory == null || player == null) {
            state.error = "libVLC 未就绪，无法播放。请在设置中指定 VLC 路径。"
            state.active = true
            return
        }
        settingsSnapshot = settings
        currentRequest = request
        pendingRequest = request
        attempts = emptyList()
        attemptIndex = 0
        attemptFinished = false
        state.resetFor(request)
        state.volume = settings.volume
        player?.audio()?.setVolume(settings.volume)
        player?.controls()?.setRate(1f)
        if (surfaceReady()) {
            scope.launch { startPendingIfReady() }
        } else {
            state.statusText = "等待视频输出…"
        }
    }

    private fun startPendingIfReady() {
        if (!surfaceReady()) {
            state.statusText = "等待视频输出…"
            return
        }
        val request = pendingRequest ?: return
        pendingRequest = null
        currentRequest = request
        val built = PlaybackLadder.build(request, settingsSnapshot.hardwareDecode, settingsSnapshot.maxQuality)
        if (built.isEmpty()) {
            state.error = "没有可播放的线路"
            state.buffering = false
            return
        }
        attempts = built
        attemptIndex = 0
        state.attempts = built.map { it.label }
        state.statusText = "连接中…"
        startAttempt()
    }

    private fun startAttempt() {
        val request = currentRequest ?: return
        val p = player ?: return
        val attempt = attempts.getOrNull(attemptIndex) ?: return
        watchJob?.cancel()
        generation++
        val gen = generation
        attemptFinished = false
        state.attemptIndex = attemptIndex
        state.attemptLabel = attempt.label
        state.softwareDecode = attempt.softwareDecode
        state.playing = false
        state.paused = false
        state.buffering = true
        state.timeMs = 0
        state.lengthMs = 0
        state.videoWidth = 0
        state.videoHeight = 0
        state.statusText = "连接中：${attempt.label}"
        Log.i("Player", "attempt ${attemptIndex + 1}/${attempts.size}: ${attempt.label} url=${attempt.url}")

        val options = buildOptions(request, attempt)
        try {
            p.media().play(attempt.url, *options.toTypedArray())
        } catch (t: Throwable) {
            Log.e("Player", "启动播放失败", t)
            fail("启动播放失败：${t.message}")
            return
        }
        watchJob = scope.launch { watch(gen, request, attempt) }
    }

    private suspend fun watch(gen: Int, request: PlaybackRequest, attempt: PlaybackLadder.Attempt) {
        val startedAt = System.currentTimeMillis()
        var ready = false
        while (scope.isActive && gen == generation) {
            delay(POLL_MS)
            if (gen != generation) return
            val p = player ?: return
            val status = runCatching { p.status() }.getOrNull() ?: return
            val time = status.time()
            val length = status.length()
            state.timeMs = time
            state.lengthMs = length
            state.playing = status.isPlaying
            state.paused = status.state() == VlcState.PAUSED
            state.buffering = status.state() == VlcState.OPENING || status.state() == VlcState.BUFFERING
            val dimension = runCatching { p.video().videoDimension() }.getOrNull()
            if (dimension != null) {
                state.videoWidth = dimension.width
                state.videoHeight = dimension.height
            }
            if (status.isPlaying) state.statusText = if (request.isLive) "直播播放中" else "播放中"
            val elapsed = System.currentTimeMillis() - startedAt
            if (time > 0 || (status.isPlaying && status.videoOutputs() > 0)) ready = true

            if (status.state() == VlcState.ERROR) {
                fail("VLC 解码错误")
                return
            }
            if (!ready && elapsed > timeoutFor(request)) {
                fail("连接超时（${timeoutFor(request) / 1000}s 内未出画面）")
                return
            }
            if (ready && elapsed > BLACK_SCREEN_GRACE_MS && status.videoOutputs() == 0 && !status.state().name.contains("PAUSED")) {
                fail("无视频输出（黑屏，疑似硬解失败）")
                return
            }
            if (ready && status.state() == VlcState.ENDED) {
                if (!request.isLive) {
                    state.playing = false
                    state.statusText = "播放结束"
                    return
                }
                fail("直播流已结束")
                return
            }
        }
    }

    private fun fail(reason: String) {
        if (attemptFinished) return
        attemptFinished = true
        watchJob?.cancel()
        val failed = attempts.getOrNull(attemptIndex)
        if (attemptIndex + 1 < attempts.size) {
            val next = attempts[attemptIndex + 1]
            state.notice = "${failed?.label ?: "当前线路"} 播放失败（$reason），自动切换 → ${next.label}"
            state.usedFallback = true
            state.statusText = "自动降级：${next.label}"
            Log.w("Player", state.notice.orEmpty())
            attemptIndex++
            scope.launch { startAttempt() }
        } else {
            state.playing = false
            state.buffering = false
            state.statusText = "播放失败"
            state.error = "播放失败：$reason（已尝试 ${attempts.size} 个线路/解码方式）"
            Log.e("Player", state.error.orEmpty())
        }
    }

    private fun timeoutFor(request: PlaybackRequest): Long = if (request.isLive) 20_000 else 15_000

    private fun buildOptions(request: PlaybackRequest, attempt: PlaybackLadder.Attempt): List<String> {
        val ua = request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: Http.DEFAULT_UA
        val referer = request.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: Http.DEFAULT_REFERER
        val options = mutableListOf(
            ":network-caching=${if (request.isLive) 1500 else 800}",
            ":http-referrer=$referer",
            ":http-user-agent=$ua",
        )
        if (request.isLive) options += ":live-caching=1500"
        if (attempt.softwareDecode) options += ":avcodec-hw=none"
        return options
    }

    fun togglePause() {
        val p = player ?: return
        if (!state.active) return
        if (state.paused) {
            p.controls().play()
            state.paused = false
        } else {
            p.controls().setPause(true)
            state.paused = true
        }
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val status = runCatching { p.status() }.getOrNull() ?: return
        if (currentRequest?.isLive == true || !status.isSeekable) {
            p.controls().skipTime(deltaMs)
        } else {
            val target = (status.time() + deltaMs).coerceIn(0, maxOf(status.length(), 0))
            p.controls().setTime(target)
        }
        state.timeMs = (status.time() + deltaMs).coerceAtLeast(0)
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        if (currentRequest?.isLive == true) return
        p.controls().setTime(ms.coerceAtLeast(0))
        state.timeMs = ms
    }

    fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 100)
        player?.audio()?.setVolume(v)
        state.volume = v
    }

    fun setRate(rate: Float) {
        val r = rate.coerceIn(0.25f, 4f)
        player?.controls()?.setRate(r)
        state.rate = r
    }

    fun stop() {
        generation++
        watchJob?.cancel()
        watchJob = null
        pendingRequest = null
        currentRequest = null
        runCatching { player?.controls()?.stop() }
        state.active = false
        state.playing = false
        state.paused = false
        state.buffering = false
        state.statusText = ""
        state.error = null
        state.notice = null
    }

    fun release() {
        stop()
        releaseInternal()
    }

    private fun releaseInternal() {
        generation++
        watchJob?.cancel()
        watchJob = null
        runCatching { player?.release() }
        player = null
        runCatching { factory?.release() }
        factory = null
        boundCanvas = null
        state.hasSurface = false
    }

    companion object {
        private const val POLL_MS = 350L

        /** 出画面后宽限多久仍无视频输出则判定黑屏（给 HLS 起播留余量）。 */
        private const val BLACK_SCREEN_GRACE_MS = 9_000L
    }
}
