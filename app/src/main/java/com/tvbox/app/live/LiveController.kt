package com.tvbox.app.live

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import com.tvbox.app.core.TvLog
import com.tvbox.app.data.MediaUrl
import com.tvbox.app.player.DeviceCapabilities
import com.tvbox.app.player.PlaybackInfoSource
import com.tvbox.app.player.PlaybackStatsSource
import com.tvbox.app.player.PlayerController
import com.tvbox.app.player.PlayerEvent
import com.tvbox.app.player.PlayerEventSource
import com.tvbox.app.player.PlayerLiveOps
import com.tvbox.app.player.PlayerProfileControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 直播控制（只负责“频道 + 线路 + EPG + 切台防抖”，播放本身交给 PlayerController）。
 *
 * 关键行为（PLAYBACK_SPEC §2/§3/§5、PLAN_LIVE §6）：
 * - 频道列表：分组 + 组内排序 + 全量台号（1 起），DPAD 焦点预览 800ms 防抖；
 * - CH+/CH- 立即切上一个/下一个；数字键 1.5s 内确认直达；
 * - 失败：按线路 priority 最多试 3 条 → 全失败自动跳到下一个可用频道（最多 3 跳），不黑屏卡死；
 * - 频道信息：台标/分组/实时分辨率码率编码（PlaybackInfo）/备用线路数/起播延迟/简介/EPG；
 * - EPG：XMLTV（gzip）+ 本地缓存，进入拉一次 + 30 分钟刷新；无匹配显示「暂无节目单」；
 * - 倍速：直播不支持（PlayerController 内部拦截，这里只负责提示）；
 * - DVR：仅 HLS 窗口内允许回退，超窗提示「该频道不支持回看」。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class LiveController(
    context: Context,
    private val player: PlayerController,
    private val seedRepository: LiveSeedRepository = LiveSeedRepository(context),
    private val epgUrlsProvider: () -> List<String> = { emptyList() },
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "tvbox_live",
        Context.MODE_PRIVATE,
    ),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext = context.applicationContext
    private val epgRepository = EpgRepository(
        cacheFile = File(context.filesDir, "epg/epg_cache.txt"),
        // 与播放共用“系统根 + ISRG 根”的客户端，Android 6 上 EPG 的 HTTPS 才不会握手失败
        client = com.tvbox.app.player.PlayerHttp.playbackClient(context.applicationContext)
            .newBuilder()
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
    )

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val profile: PlayerProfileControl? = player as? PlayerProfileControl
    private val liveOps: PlayerLiveOps? = player as? PlayerLiveOps

    private var pendingSwitch: Job? = null
    private var retryJob: Job? = null
    private var numberJob: Job? = null
    private var epgLoopJob: Job? = null
    private var slowStartJob: Job? = null

    private var started = false
    private var epgUrls: List<String> = emptyList()
    private var epgIndex: EpgIndex = EpgIndex.EMPTY
    private var lastEpgFetchAt = 0L

    /** 「4K 优先」设置：默认关；仅设备通过 MediaCodec 4K 探测时生效 */
    private var prefer4K = false

    /**
     * API≤23 / MStar 6A938 的 1080p 黑屏兜底：默认把轨道上限压到 720p。
     * 设置页可开「强制 1080p」（本机可能黑屏）；默认关。
     */
    private var force1080 = false
    private val legacyMstarDevice: Boolean
        get() = android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.M

    private val deviceSupports4K: Boolean get() = DeviceCapabilities.caps.supports4K
    private val attemptedUrls = LinkedHashSet<String>()
    private val failedChannelIds = HashSet<String>()
    private var hopCount = 0
    private var lastSwitchAt = 0L

    // PLAYER_PERF 埋点
    private var lastPrepareMs = 0L
    private var zapAt = 0L
    private var zapFrom = "-"
    private var zapTo = "-"
    private var degradedFrom = 0

    // 源冷却（连续 3 次失败 → degraded 10 分钟）
    private val lineFailures = HashMap<String, Int>()
    private val lineCooldownUntil = HashMap<String, Long>()
    private var warmJob: Job? = null
    private val statsSource: PlaybackStatsSource? = player as? PlaybackStatsSource

    init {
        // 播放器实时参数 -> 状态（分辨率/码率/编码/DVR/音画校准）
        val infoSource = player as? PlaybackInfoSource
        if (infoSource != null) {
            scope.launch {
                infoSource.playbackInfo.collect { info ->
                    _state.update { it.copy(info = info) }
                }
            }
        }
        // 播放器事件 -> 切线路 / 切台 / 提示
        val eventSource = player as? PlayerEventSource
        if (eventSource != null) {
            scope.launch {
                eventSource.events.collect { event -> onPlayerEvent(event) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 启动
    // ------------------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        scope.launch {
            _state.update { it.copy(loading = true) }
            val snapshot = seedRepository.load()
            val (groups, grouped) = buildGroups(snapshot.groups, snapshot.channels)
            prefer4K = prefs.getBoolean(KEY_PREFER_4K, false) && deviceSupports4K
            force1080 = prefs.getBoolean(KEY_FORCE_1080, false)
            _state.update {
                it.copy(
                    loading = false,
                    seedAvailable = snapshot.channels.isNotEmpty(),
                    seedSource = snapshot.source,
                    groups = groups,
                    channels = snapshot.channels,
                    groupedChannels = grouped,
                    prefer4K = prefer4K,
                    force1080 = force1080,
                    deviceSupports4K = deviceSupports4K,
                    message = snapshot.note,
                    error = if (snapshot.channels.isEmpty()) {
                        snapshot.note ?: "暂无频道数据"
                    } else {
                        null
                    },
                )
            }
            if (snapshot.channels.isEmpty()) {
                TvLog.w(TAG, "no channels available: ${snapshot.note}")
                return@launch
            }
            // 设备不支持 4K / 未开启 4K 优先时，把播放器轨道上限压到 1080p
            applyQualityCap()
            prewarmDns(snapshot.channels)

            epgUrls = epgUrlsProvider()
                .map { it.trim() }
                .filter { it.startsWith("http") }
                .distinct()
                .take(MAX_EPG_URLS)
                .ifEmpty { epgUrls }

            val cached = epgRepository.loadCache()
            if (cached.programCount > 0) {
                epgIndex = cached
                updateEpgNow()
            }
            if (epgUrls.isNotEmpty()) {
                // EPG 不阻塞起播：放在独立协程，网络慢也先出画面
                startEpgLoop()
                scope.launch { refreshEpg(force = false) }
            }

            val lastId = prefs.getString(KEY_LAST_CHANNEL, null)
            val target = snapshot.channels.firstOrNull { it.id == lastId }
                ?: snapshot.channels
                    .filter { it.hdZone && it.bestHeight >= 1080 }
                    .maxByOrNull { it.bestHeight }
                ?: snapshot.channels.firstOrNull()
            if (target != null) playChannel(target, resetHops = true)
        }
    }

    fun release() {
        started = false
        pendingSwitch?.cancel()
        pendingSwitch = null
        retryJob?.cancel()
        retryJob = null
        numberJob?.cancel()
        numberJob = null
        epgLoopJob?.cancel()
        epgLoopJob = null
        slowStartJob?.cancel()
        slowStartJob = null
        warmJob?.cancel()
        warmJob = null
        BandwidthGate.zapping = false
        // 离开直播页后把播放器恢复点播档位（否则同一实例上点播倍速会被直播拦截）
        profile?.setLiveMode(false)
        scope.cancel()
        TvLog.i(TAG, "released")
    }

    // ------------------------------------------------------------------
    // 切台
    // ------------------------------------------------------------------

    /**
     * DPAD 在频道列表内移动时调用（800ms 防抖，只加载最后一次焦点）。
     * 连按上下只会在停稳后发起一次播放，避免“连按狂切”把 codec 拖死。
     */
    fun preview(channel: LiveChannel) {
        if (channel.id == _state.value.current?.id && !_state.value.switching) return
        pendingSwitch?.cancel()
        attemptedUrls.clear()
        retryJob?.cancel()
        // 焦点落定 → 立刻进入“切台态”，UI 可暂停 Coil/分页
        BandwidthGate.zapping = true
        // 焦点停留 ≥300ms：只 GET playlist 头预热（不 prepare、不解码）
        warmJob?.cancel()
        warmJob = scope.launch {
            delay(FOCUS_WARM_MS)
            channel.bestLine?.url?.let { warmUp(it) }
        }
        _state.update {
            it.copy(
                current = channel,
                lineIndex = 0,
                switching = true,
                switchingStartedAt = now(),
                switchLatencyMs = 0L,
                error = null,
                overlay = LiveOverlay.NONE,
                epg = epgFor(channel),
                message = healthNotice(channel),
            )
        }
        pendingSwitch = scope.launch {
            delay(SWITCH_DEBOUNCE_MS)
            if (_state.value.current?.id == channel.id) {
                playChannel(channel, resetHops = true)
            }
        }
    }

    /** 立即切台（CH+/CH-、数字直达、列表点击）。 */
    fun switchTo(channelId: String) {
        val channel = _state.value.channels.firstOrNull { it.id == channelId } ?: return
        failedChannelIds.clear()
        playChannel(channel, resetHops = true)
    }

    fun nextChannel() = stepChannel(1)

    fun previousChannel() = stepChannel(-1)

    /** 不使用频道列表焦点时，UI 可直接把 DPAD_UP/DOWN 转成预览切台（同样 800ms 防抖）。 */
    fun onDpadSwitch(direction: Int) {
        val st = _state.value
        if (st.channels.isEmpty()) return
        val index = st.channels.indexOfFirst { it.id == st.current?.id }
        if (index < 0) {
            preview(st.channels.first())
            return
        }
        val target = st.channels[(index + direction + st.channels.size) % st.channels.size]
        preview(target)
    }

    /** 频道信息面板里手动选线路：保留当前播放进度。 */
    fun selectLine(index: Int) {
        val st = _state.value
        val channel = st.current ?: return
        val line = channel.lines.getOrNull(index) ?: return
        if (line.displayHeight >= 2000) {
            if (!prefer4K) {
                _state.update { it.copy(message = "该线路为 4K，请先在设置中开启「4K 优先」") }
                return
            }
            if (!DeviceCapabilities.caps.supportsLine(line.measuredCodec, line.displayHeight)) {
                _state.update { it.copy(message = "当前设备不支持该 4K 编码，已保持原线路") }
                return
            }
        }
        pendingSwitch?.cancel()
        attemptedUrls.clear()
        attemptedUrls += line.url
        retryJob?.cancel()
        profile?.setLiveMode(true)
        _state.update {
            it.copy(
                lineIndex = index,
                switching = true,
                switchingStartedAt = now(),
                switchLatencyMs = 0L,
                message = "正在切换 ${line.label}",
                error = null,
            )
        }
        player.play(
            MediaUrl(label = "${channel.name} ${line.label}", url = line.url),
            player.positionMs.value,
        )
        TvLog.i(TAG, "manual line ${index + 1}/${channel.lines.size} for ${channel.name}")
    }

    private fun stepChannel(delta: Int) {
        val st = _state.value
        if (st.channels.isEmpty()) return
        val index = st.channels.indexOfFirst { it.id == st.current?.id }
        if (index < 0) {
            failedChannelIds.clear()
            playChannel(st.channels.first(), resetHops = true)
            return
        }
        val target = st.channels[(index + delta + st.channels.size) % st.channels.size]
        failedChannelIds.clear()
        playChannel(target, resetHops = true)
    }

    private fun playChannel(channel: LiveChannel, resetHops: Boolean) {
        if (resetHops) {
            hopCount = 0
            failedChannelIds.clear()
        }
        retryJob?.cancel()
        attemptedUrls.clear()
        profile?.setLiveMode(true)
        lastSwitchAt = now()
        zapFrom = _state.value.current?.name ?: "-"
        zapTo = channel.name
        zapAt = lastSwitchAt
        lastPrepareMs = 0L
        degradedFrom = 0
        BandwidthGate.zapping = true
        _state.update {
            it.copy(
                current = channel,
                lineIndex = 0,
                switching = true,
                switchingStartedAt = lastSwitchAt,
                switchLatencyMs = 0L,
                overlay = LiveOverlay.NONE,
                error = null,
                message = healthNotice(channel),
                epg = epgFor(channel),
            )
        }
        playFirstLine(channel)
    }

    private fun playFirstLine(channel: LiveChannel) {
        val line = nextCandidate(channel)
        if (line == null) {
            if (channel.lines.any { it.displayHeight >= 2000 } && !prefer4K) {
                _state.update { it.copy(switching = false, error = "该频道仅 4K 源，请开启「4K 优先」") }
            } else {
                onChannelFailed(channel)
            }
            return
        }
        if (line.displayHeight > safetyCap()) {
            _state.update {
                it.copy(message = "该频道无 ≤720p 源，尝试 ${line.displayHeight}p（本机可能黑屏）")
            }
        }
        playLine(channel, line, "first")
    }

    private fun playLine(channel: LiveChannel, line: LiveLine, reason: String = "fallback") {
        if (line.url in attemptedUrls) {
            nextCandidate(channel)?.let { playLine(channel, it, reason) } ?: onChannelFailed(channel)
            return
        }
        if (attemptedUrls.size >= MAX_LINE_ATTEMPTS) {
            onChannelFailed(channel)
            return
        }
        attemptedUrls += line.url
        _state.update {
            it.copy(
                lineIndex = line.index,
                switching = true,
                switchingStartedAt = if (it.switchingStartedAt > 0L) it.switchingStartedAt else now(),
            )
        }
        player.play(
            MediaUrl(label = "${channel.name} ${line.label}", url = line.url),
            0L,
        )
        startSlowStartFallback(channel, line)
        TvLog.i(
            TAG,
            "switch ${channel.name} line=${line.index + 1}/${channel.lines.size} " +
                "tier=${line.tier} h=${line.displayHeight} reason=$reason " +
                "(attempt ${attemptedUrls.size}) url=${line.url}",
        )
    }

    /**
     * 高清源起播慢的兜底（CCTV-1 1080p 约 9s，720p 约 4.5s）：
     * 5s 未出首帧就并行预热下一档 URL，并切到低一档；单 codec 设备不能同时解码两路，
     * 所以“并行”= 预热连接 + 立即切档，用户不需要干等 8s 看门狗。
     */
    private fun startSlowStartFallback(channel: LiveChannel, line: LiveLine) {
        slowStartJob?.cancel()
        // 只对高清源（≥720p）做兜底；低档源没有可降的档
        if (line.displayHeight < 720) return
        val lower = channel.rankedLines.firstOrNull {
            it.autoPlayable && it.displayHeight in 1 until line.displayHeight && it.url !in attemptedUrls
        } ?: return
        slowStartJob = scope.launch {
            delay(SLOW_START_MS)
            val state = _state.value
            if (state.current?.id != channel.id || !state.switching) return@launch
            if (attemptedUrls.lastOrNull() != line.url) return@launch
            _state.update { it.copy(message = "起播较慢，已切换到 ${lower.displayHeight}p") }
            degradedFrom = line.displayHeight
            warmUp(lower.url)
            playLine(channel, lower, "slow_start")
        }
    }

    private fun warmUp(url: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", com.tvbox.app.player.PlayerHttp.USER_AGENT)
                    .build()
                com.tvbox.app.player.PlayerHttp.warmClient(appContext)
                    .newCall(request)
                    .execute()
                    .close()
                TvLog.d(TAG, "warm-up playlist ok: $url")
            }
        }
    }

    /**
     * 自动选源：只用实测可播的源；4K 必须“设备支持 + 开启 4K 优先”；
     * 探测失败（tier=error）/无视频轨永不自动选；fallback_only 排最后。
     */
    private fun candidates(channel: LiveChannel): List<LiveLine> {
        val caps = DeviceCapabilities.caps
        val cap = safetyCap()
        val playable = channel.rankedLines.filter { line ->
            if (!line.autoPlayable) return@filter false
            if (line.displayHeight < 2000) return@filter true
            prefer4K && caps.supportsLine(line.measuredCodec, line.displayHeight)
        }
        val withinCap = playable.filter { it.displayHeight <= cap || it.displayHeight <= 0 }
        val capped = if (withinCap.isNotEmpty()) withinCap else playable
        val current = now()
        val ready = capped.filter { (lineCooldownUntil[it.url] ?: 0L) <= current }
        // 全部在冷却时仍允许尝试，避免整台不可用（冷却只用于排序降权）
        return if (ready.isNotEmpty()) ready else capped
    }

    /** 当前设备安全档位：MStar 老平台默认 720p（1080p 黑屏），可强制 1080p。 */
    private fun safetyCap(): Int = when {
        prefer4K && deviceSupports4K -> 2160
        legacyMstarDevice && !force1080 -> 720
        else -> 1080
    }

    private fun markLineFailed(url: String) {
        val fails = (lineFailures[url] ?: 0) + 1
        lineFailures[url] = fails
        if (fails >= MAX_SOURCE_FAILS) {
            lineFailures[url] = 0
            lineCooldownUntil[url] = now() + SOURCE_COOLDOWN_MS
            TvLog.w(TAG, "source degraded for 10min (3 consecutive failures): $url")
        }
    }

    /** 进直播页预热当前分组前 10 个频道 host（DNS），复用 OkHttp 连接池。 */
    private fun prewarmDns(channels: List<LiveChannel>) {
        scope.launch(Dispatchers.IO) {
            val hosts = channels.take(10).mapNotNull { channel ->
                val url = channel.bestLine?.url ?: return@mapNotNull null
                runCatching { java.net.URI(url).host }.getOrNull()
            }.filterNotNull().distinct()
            hosts.forEach { host ->
                runCatching { java.net.InetAddress.getAllByName(host) }
            }
            TvLog.i(TAG, "dns prewarm: ${hosts.size} hosts")
        }
    }

    private fun nextCandidate(channel: LiveChannel): LiveLine? =
        candidates(channel).firstOrNull { it.url !in attemptedUrls }

    private fun applyQualityCap() {
        val cap = safetyCap()
        player.setQuality(cap)
        TvLog.i(TAG, "quality cap=${cap}p (legacyMstar=$legacyMstarDevice force1080=$force1080 prefer4K=$prefer4K)")
    }

    /** 设置项「强制 1080p（本机可能黑屏）」：默认关，仅影响 API≤23 的 720p 兜底。 */
    fun setForce1080(enabled: Boolean) {
        force1080 = enabled
        prefs.edit().putBoolean(KEY_FORCE_1080, enabled).apply()
        // 全局播放器硬上限（点播/短视频也复用同一个 PlayerControllerImpl）
        (player as? com.tvbox.app.player.PlayerControllerImpl)?.setAllowHighQuality(enabled)
        applyQualityCap()
        _state.update {
            it.copy(
                force1080 = enabled,
                message = if (enabled) "已强制 1080p/4K（本机可能黑屏，仅调试用）" else "已回到 720p 安全模式",
            )
        }
        _state.value.current?.let { playChannel(it, resetHops = true) }
        TvLog.i(TAG, "force1080=$enabled")
    }

    /** 设置项「4K 优先」：默认关；打开后仅对支持设备生效，并立刻按新顺序重选当前频道。 */
    fun setPrefer4K(enabled: Boolean) {
        val supported = deviceSupports4K
        val target = enabled && supported
        if (target == prefer4K && enabled == target) {
            // 设备不支持时给出明确反馈
            if (enabled && !supported) {
                _state.update { it.copy(message = "当前设备不支持 4K，已保持 1080p 上限") }
            }
            return
        }
        prefer4K = target
        prefs.edit().putBoolean(KEY_PREFER_4K, target).apply()
        applyQualityCap()
        _state.update {
            it.copy(
                prefer4K = target,
                deviceSupports4K = supported,
                message = when {
                    enabled && !supported -> "当前设备不支持 4K，已保持 1080p 上限"
                    target -> "4K 优先已开启（最高 2160p）"
                    else -> "4K 优先已关闭（最高 1080p）"
                },
            )
        }
        _state.value.current?.let { playChannel(it, resetHops = true) }
        TvLog.i(TAG, "prefer4K=$target deviceSupports4K=$supported")
    }

    private fun buildGroups(
        seedGroups: List<LiveGroup>,
        channels: List<LiveChannel>,
    ): Pair<List<LiveGroup>, Map<String, List<LiveChannel>>> {
        val grouped = LinkedHashMap<String, List<LiveChannel>>()
        channels.groupBy { it.groupName }.forEach { (name, list) -> grouped[name] = list }
        val hd = channels.filter { it.hdZone }
        val groups = ArrayList<LiveGroup>(seedGroups.size + 1)
        if (hd.isNotEmpty()) {
            groups += LiveGroup("hd", "高清专区", 0, hd.size)
            grouped["高清专区"] = hd
        }
        seedGroups.forEach { group ->
            groups += group.copy(channelCount = grouped[group.name]?.size ?: 0)
        }
        return groups to grouped
    }

    // ------------------------------------------------------------------
    // 播放器事件
    // ------------------------------------------------------------------

    private fun onPlayerEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.FirstFrame -> onFirstFrame()
            is PlayerEvent.Prepared -> lastPrepareMs = event.prepareMs
            is PlayerEvent.NeedsFallback -> handleFailure(event.code, event.reason, event.url)
            is PlayerEvent.NoData -> Unit // NeedsFallback 紧随其后，避免重复处理
            is PlayerEvent.AutoDowngrade -> _state.update {
                it.copy(message = "网络/性能不足，已切 ${event.toHeight}p")
            }
            is PlayerEvent.BufferEscalated -> _state.update {
                it.copy(
                    message = if (event.qualityDowngraded) {
                        "音频欠载频繁，已降画质到 720p"
                    } else {
                        "音频缓冲不足，已提高缓冲"
                    },
                )
            }
            is PlayerEvent.Message -> {
                if (event.text.contains("已切 720p") || event.text.contains("已降画质")) {
                    degradedFrom = _state.value.currentLine?.displayHeight ?: _state.value.info.height
                }
                _state.update { it.copy(message = event.text) }
            }
            is PlayerEvent.Resynced -> Unit
        }
    }

    private fun onFirstFrame() {
        slowStartJob?.cancel()
        slowStartJob = null
        BandwidthGate.zapping = false
        val st = _state.value
        val channel = st.current ?: return
        st.currentLine?.url?.let { lineFailures.remove(it) }
        val latency = if (st.switchingStartedAt > 0L) now() - st.switchingStartedAt else 0L
        hopCount = 0
        failedChannelIds.clear()
        prefs.edit().putString(KEY_LAST_CHANNEL, channel.id).apply()
        _state.update {
            it.copy(
                switching = false,
                switchLatencyMs = latency,
                error = null,
                message = null,
            )
        }
        updateEpgNow()
        val src = st.currentLine?.displayHeight?.takeIf { it > 0 }?.let { "${it}p" } ?: "auto"
        val rebuffer = statsSource?.stats?.value?.rebufferCount ?: 0
        // qa 验收埋点（PLAYER_PERF §6）
        TvLog.i(
            "PLAYER_EVT",
            "action=zap from=$zapFrom to=${channel.name} t_prepare_ms=$lastPrepareMs " +
                "t_first_frame_ms=$latency src=$src rebuffer=$rebuffer " +
                "degraded=${if (degradedFrom > 0) 1 else 0} src_prev=${if (degradedFrom > 0) "${degradedFrom}p" else "-"}",
        )
        TvLog.i(TAG, "first frame ${channel.name} in ${latency}ms line=${st.lineIndex + 1}")
    }

    private fun handleFailure(code: String, reason: String, url: String?) {
        val channel = _state.value.current ?: return
        // 没有任何“已尝试线路”时收到的错误属于上一个流/上一个频道，忽略
        if (attemptedUrls.isEmpty()) return
        // 旧线路的迟到错误：忽略，避免把刚起播的频道踢掉
        if (url != null && attemptedUrls.last() != url) return
        attemptedUrls.lastOrNull()?.let { markLineFailed(it) }
        retryJob?.cancel()
        retryJob = scope.launch {
            val noData = code.contains("NO_DATA", true) || code.contains("TIMEOUT", true)
            if (!noData) delay(RETRY_DELAY_MS)
            val next = nextCandidate(channel)
            if (next != null && attemptedUrls.size < MAX_LINE_ATTEMPTS) {
                val previous = channel.lines.firstOrNull { it.url == attemptedUrls.lastOrNull() }
                val downgraded = previous != null &&
                    next.displayHeight > 0 && next.displayHeight < previous.displayHeight
                if (downgraded) degradedFrom = previous!!.displayHeight
                _state.update {
                    it.copy(
                        message = if (downgraded) {
                            "已切换到 ${next.displayHeight}p（$reason）"
                        } else {
                            "$reason（尝试备用线路 ${attemptedUrls.size + 1}/$MAX_LINE_ATTEMPTS）"
                        },
                    )
                }
                playLine(channel, next, "fallback")
            } else {
                onChannelFailed(channel)
            }
        }
    }

    private fun onChannelFailed(channel: LiveChannel) {
        slowStartJob?.cancel()
        slowStartJob = null
        BandwidthGate.zapping = false
        retryJob?.cancel()
        failedChannelIds += channel.id
        val tried = attemptedUrls.size.coerceAtLeast(1)
        val only4k = candidates(channel).isEmpty() && channel.lines.any { it.displayHeight >= 2000 } && !prefer4K
        _state.update {
            it.copy(
                switching = false,
                error = if (only4k) {
                    "该频道仅 4K 源，请开启「4K 优先」"
                } else {
                    "该频道暂不可用（已尝试 $tried 条线路）"
                },
            )
        }
        TvLog.w(TAG, "channel failed: ${channel.name} tried=$tried hops=$hopCount")
        if (hopCount >= MAX_HOPS) {
            _state.update { it.copy(error = "该频道暂不可用，请稍后重试") }
            return
        }
        hopCount++
        retryJob = scope.launch {
            delay(HOP_DELAY_MS)
            val next = findNextPlayable(channel)
            if (next != null) {
                _state.update { it.copy(message = "自动切换到 ${next.name}") }
                playChannel(next, resetHops = false)
            } else {
                _state.update { it.copy(error = "频道暂不可用，请检查网络后重试") }
            }
        }
    }

    private fun findNextPlayable(from: LiveChannel): LiveChannel? {
        val list = _state.value.channels
        if (list.isEmpty()) return null
        val start = list.indexOfFirst { it.id == from.id }.let { if (it < 0) 0 else it }
        for (offset in 1..list.size) {
            val candidate = list[(start + offset) % list.size]
            if (candidate.id in failedChannelIds) continue
            if (candidates(candidate).isEmpty()) continue
            return candidate
        }
        return null
    }

    // ------------------------------------------------------------------
    // EPG
    // ------------------------------------------------------------------

    fun setEpgUrls(urls: List<String>) {
        val normalized = urls.map { it.trim() }.filter { it.startsWith("http") }.distinct().take(MAX_EPG_URLS)
        if (normalized == epgUrls) return
        epgUrls = normalized
        lastEpgFetchAt = 0L
        if (normalized.isNotEmpty()) {
            startEpgLoop()
            scope.launch { refreshEpg(force = true) }
        }
    }

    suspend fun refreshEpg(force: Boolean = false) {
        if (_state.value.switching) return // 切台期间不刷 EPG，把带宽留给首片
        val urls = epgUrls
        if (urls.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && epgIndex.programCount > 0 && now - lastEpgFetchAt < EPG_REFRESH_MS) return
        val fresh = epgRepository.refresh(urls, now)
        if (fresh.programCount > 0) {
            epgIndex = fresh
            lastEpgFetchAt = now
            updateEpgNow()
        }
    }

    private fun startEpgLoop() {
        if (epgLoopJob?.isActive == true) return
        epgLoopJob = scope.launch {
            var minutes = 0
            while (isActive) {
                delay(60_000)
                minutes++
                updateEpgNow() // 进度条随时间推进
                if (minutes % EPG_REFRESH_MINUTES == 0) refreshEpg(force = true)
            }
        }
    }

    private fun updateEpgNow() {
        if (_state.value.switching) return
        _state.update { it.copy(epg = epgFor(it.current)) }
    }

    private fun epgFor(channel: LiveChannel?): EpgNowNext =
        if (channel == null || epgIndex.programCount == 0) {
            EpgNowNext.EMPTY
        } else {
            epgIndex.nowNext(channel, System.currentTimeMillis())
        }

    // ------------------------------------------------------------------
    // DVR / 倍速
    // ------------------------------------------------------------------

    /** 直播 DVR：仅在 HLS 窗口内可回退，超窗提示（PLAYBACK_SPEC §5）。 */
    fun seekBack(seconds: Int = 10) {
        val handled = liveOps?.seekBack(seconds) ?: false
        if (handled) return
        if (_state.value.info.isLive) {
            _state.update { it.copy(message = "该频道不支持回看") }
        } else {
            player.seekTo((player.positionMs.value - seconds * 1000L).coerceAtLeast(0L))
        }
    }

    fun seekForward(seconds: Int = 10) {
        val handled = liveOps?.seekForward(seconds) ?: false
        if (!handled) {
            _state.update { it.copy(message = "直播不能快进（仅支持 DVR 回退）") }
        }
    }

    fun seekToLiveEdge() {
        liveOps?.seekToLiveEdge()
    }

    fun isDvrSeekable(): Boolean = liveOps?.isDvrSeekable() ?: false

    /** 直播倍速置灰的统一提示。 */
    fun requestSpeed(speed: Float) {
        if (_state.value.info.isLive) {
            _state.update { it.copy(message = "直播不支持倍速（HLS 拉伸会造成音画漂移）") }
        } else {
            player.setSpeed(speed)
        }
    }

    // ------------------------------------------------------------------
    // 浮层 / 按键 / 数字台号
    // ------------------------------------------------------------------

    fun toggleOverlay(overlay: LiveOverlay) {
        _state.update { it.copy(overlay = if (it.overlay == overlay) LiveOverlay.NONE else overlay) }
    }

    /** OK 确认：收起浮层，并把防抖中的预览频道立刻起播。 */
    fun confirmOverlay() {
        val state = _state.value
        val channel = state.current
        pendingSwitch?.cancel()
        pendingSwitch = null
        _state.update { it.copy(overlay = LiveOverlay.NONE) }
        if (channel != null && state.switching) {
            playChannel(channel, resetHops = true)
        }
    }

    /** 浮层内点选频道：立即切台并收起浮层。 */
    fun confirmChannel(channel: LiveChannel) {
        pendingSwitch?.cancel()
        pendingSwitch = null
        _state.update { it.copy(overlay = LiveOverlay.NONE) }
        playChannel(channel, resetHops = true)
    }

    /** LEFT 键：浮层关闭时呼出，已打开时收起。 */
    fun onLeftPressed(): Boolean {
        return when (_state.value.overlay) {
            LiveOverlay.NONE -> {
                toggleOverlay(LiveOverlay.CHANNEL_GRID)
                true
            }
            LiveOverlay.CHANNEL_GRID -> {
                dismissOverlay()
                true
            }
            LiveOverlay.CHANNEL_INFO -> {
                dismissOverlay()
                true
            }
        }
    }

    fun dismissOverlay() {
        _state.update { it.copy(overlay = LiveOverlay.NONE) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * @return true = 事件已消费（UI 不再处理）；DPAD 上下左右不消费（交给列表焦点）。
     */
    fun handleKey(keyCode: Int, action: Int = LiveKeys.ACTION_DOWN, repeatCount: Int = 0): Boolean {
        if (action == LiveKeys.ACTION_UP) {
            return keyCode == LiveKeys.CHANNEL_UP || keyCode == LiveKeys.CHANNEL_DOWN
        }
        if (repeatCount > 0) {
            // 长按 CH+/CH-/数字键只处理第一次，避免连续切台风暴
            if (keyCode == LiveKeys.CHANNEL_UP || keyCode == LiveKeys.CHANNEL_DOWN ||
                LiveKeys.digitOf(keyCode) != null
            ) {
                return true
            }
        }
        return when (keyCode) {
            LiveKeys.CHANNEL_UP -> {
                nextChannel()
                true
            }
            LiveKeys.CHANNEL_DOWN -> {
                previousChannel()
                true
            }
            LiveKeys.MENU -> {
                toggleOverlay(LiveOverlay.CHANNEL_GRID)
                true
            }
            LiveKeys.INFO -> {
                toggleOverlay(LiveOverlay.CHANNEL_INFO)
                true
            }
            LiveKeys.BACK -> when {
                _state.value.overlay != LiveOverlay.NONE -> {
                    dismissOverlay()
                    true
                }
                _state.value.numberEntry.isNotEmpty() -> {
                    clearNumberEntry()
                    true
                }
                else -> false
            }
            LiveKeys.DPAD_LEFT -> {
                if (_state.value.overlay != LiveOverlay.NONE) {
                    dismissOverlay()
                    true
                } else {
                    false
                }
            }
            LiveKeys.DPAD_CENTER, LiveKeys.ENTER -> when {
                _state.value.overlay != LiveOverlay.NONE -> {
                    confirmOverlay()
                    true
                }
                _state.value.numberEntry.isNotEmpty() -> {
                    confirmNumber()
                    true
                }
                else -> false
            }
            else -> {
                val digit = LiveKeys.digitOf(keyCode)
                if (digit != null) {
                    inputDigit(digit)
                    true
                } else {
                    false
                }
            }
        }
    }

    fun inputDigit(digit: Int) {
        val entry = (_state.value.numberEntry + digit).takeLast(LiveKeys.NUMBER_MAX_LENGTH)
        _state.update { it.copy(numberEntry = entry) }
        numberJob?.cancel()
        numberJob = scope.launch {
            delay(LiveKeys.NUMBER_TIMEOUT_MS)
            resolveNumber(entry)
        }
    }

    fun confirmNumber() {
        numberJob?.cancel()
        resolveNumber(_state.value.numberEntry)
    }

    fun clearNumberEntry() {
        numberJob?.cancel()
        _state.update { it.copy(numberEntry = "") }
    }

    private fun resolveNumber(entry: String) {
        if (entry.isBlank()) return
        val number = entry.toIntOrNull()
        val target = _state.value.channels.firstOrNull { it.number == number }
            ?: _state.value.channels.firstOrNull { it.id.equals(entry, ignoreCase = true) }
        if (target == null) {
            _state.update { it.copy(numberEntry = "", message = "无此台号：$entry") }
            return
        }
        _state.update { it.copy(numberEntry = "") }
        playChannel(target, resetHops = true)
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun healthNotice(channel: LiveChannel): String? = when (channel.health) {
        LiveHealth.DEGRADED -> "该频道线路不稳定，正在尝试备用线路"
        LiveHealth.FLAKY -> "线路波动，起播可能需要几秒"
        else -> null
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "Live"
        const val SWITCH_DEBOUNCE_MS = 800L   // DPAD 切台防抖（任务 §6）
        const val RETRY_DELAY_MS = 600L       // 换源间隔（PLAN_LIVE §6.2）
        const val HOP_DELAY_MS = 800L
        const val MAX_LINE_ATTEMPTS = 3       // 每频道最多 3 条备源
        const val MAX_HOPS = 3                // 全失败最多自动跳 3 个频道
        const val SLOW_START_MS = 5_000L      // 高清源 5s 未出首帧 → 预热并切低一档
        const val FOCUS_WARM_MS = 300L        // 焦点停留 ≥300ms 预热 playlist
        const val MAX_SOURCE_FAILS = 3        // 同源连续失败 3 次 → degraded 冷却
        const val SOURCE_COOLDOWN_MS = 10 * 60 * 1000L
        const val MAX_EPG_URLS = 3
        const val EPG_REFRESH_MS = 30L * 60 * 1000
        const val EPG_REFRESH_MINUTES = 30
        const val KEY_LAST_CHANNEL = "last_channel_id"
        const val KEY_PREFER_4K = "prefer_4k"
        const val KEY_FORCE_1080 = "force_1080"
    }
}
