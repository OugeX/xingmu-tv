package com.xingmu.tv.live

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.data.MediaUrl
import com.xingmu.tv.player.DeviceCapabilities
import com.xingmu.tv.player.PlaybackInfoSource
import com.xingmu.tv.player.PlaybackStatsSource
import com.xingmu.tv.player.PlayerController
import com.xingmu.tv.player.PlayerEvent
import com.xingmu.tv.player.PlayerEventSource
import com.xingmu.tv.player.PlayerLiveOps
import com.xingmu.tv.player.PlayerProfileControl
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
        client = com.xingmu.tv.player.PlayerHttp.playbackClient(context.applicationContext)
            .newBuilder()
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
    )

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    /** t30：收藏 / 最近（SharedPreferences + StateFlow，UI 直接 collect） */
    val channelPrefs = LiveChannelPrefs(context.applicationContext)

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

    // 安全模式（live_safe.json）：只在 API≤23 / MStar 老平台且未强制高清时生效
    private var allChannels: List<LiveChannel> = emptyList()
    private var baseGroups: List<LiveGroup> = emptyList()
    private var safeMode = false
    private var showAllChannels = false
    private var safeDataLoaded = false

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

    // 主机级熔断（同 host 10 分钟 2 次失败 → 冷却 30 分钟，不逐条重试）
    private val hostFailures = HashMap<String, Int>()
    private val hostCooldownUntil = HashMap<String, Long>()
    private val prefetchedUrls = HashSet<String>()
    private val prefetchedPlaylists = HashSet<String>()

    /** t25：已做过 TLS 预握手的 host（避免同一台反复握手）。多协程访问，用同步包装。 */
    private val preconnectedHosts: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet<String>())

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
        // t25 / P1-7：直播页 = 播放态，Coil 缓存压到 8MB + 暂停海报请求
        com.xingmu.tv.player.PlaybackMemoryGuard.enter(appContext)
        scope.launch {
            _state.update { it.copy(loading = true) }
            val snapshot = seedRepository.load()
            prefer4K = prefs.getBoolean(KEY_PREFER_4K, false) && deviceSupports4K
            force1080 = prefs.getBoolean(KEY_FORCE_1080, false)
            allChannels = snapshot.channels
            baseGroups = snapshot.groups
            safeDataLoaded = snapshot.channels.any { it.safe != null }
            safeMode = safeDataLoaded && legacyMstarDevice && !force1080
            showAllChannels = prefs.getBoolean(KEY_SHOW_ALL_CHANNELS, false)
            val visible = visibleChannels(allChannels)
            val (groups, grouped) = buildGroups(snapshot.groups, visible)
            val hidden = (allChannels.size - visible.size).coerceAtLeast(0)
            _state.update {
                it.copy(
                    loading = false,
                    seedAvailable = snapshot.channels.isNotEmpty(),
                    seedSource = snapshot.source,
                    groups = groups,
                    channels = visible,
                    groupedChannels = grouped,
                    prefer4K = prefer4K,
                    force1080 = force1080,
                    deviceSupports4K = deviceSupports4K,
                    safeMode = safeMode,
                    showAllChannels = showAllChannels,
                    hiddenUnsafeCount = hidden,
                    safeFilterHint = safeFilterHint(hidden),
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
            TvLog.i(
                TAG,
                "safeMode=$safeMode showAll=$showAllChannels visible=${visible.size}/${allChannels.size} " +
                    "hidden=$hidden safeData=$safeDataLoaded",
            )
            // 设备不支持 4K / 未开启 4K 优先时，把播放器轨道上限压到 1080p
            applyQualityCap()
            prewarmDns(visible)

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
            val target = visible.firstOrNull { it.id == lastId }
                ?: visible.filter { it.hdZone && it.hasSafeSource }.maxByOrNull { it.safeHeight }
                ?: visible.filter { it.hasSafeSource }.maxByOrNull { it.safeHeight }
                ?: visible.firstOrNull()
            if (target != null) {
                // 首频道先预热 playlist + 最新分片（不计入 PLAYER_EVT 起播耗时，首帧更快）
                val targetUrl = if (safeMode) target.safeLine?.url ?: target.bestLine?.url
                else target.bestLine?.url
                if (targetUrl != null) {
                    runCatching { com.xingmu.tv.player.PlayerHttp.prewarm(appContext, targetUrl) }
                }
                playChannel(target, resetHops = true)
            }
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
        // t25 / P1-7：退出直播页恢复图片缓存上限与请求
        com.xingmu.tv.player.PlaybackMemoryGuard.exit()
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
        // 焦点停留 ≥250ms：预热候选 URL（安全模式先预热 safeLine，避免与安全排序打架）
        warmJob?.cancel()
        warmJob = scope.launch {
            delay(FOCUS_WARM_MS)
            val target = if (safeMode) channel.safeLine?.url ?: channel.bestLine?.url
            else channel.bestLine?.url
            target?.let { warmUp(it) }
            // t25 / PERF_ROADMAP P0-2：预连接相邻频道（CH+/CH-），连切时命中已建好的 TLS 连接
            preconnectNeighbors(channel)
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

    private fun playChannel(channel: LiveChannel, resetHops: Boolean, bypassUnsafeConfirm: Boolean = false) {
        // 安全模式 + 用户打开了「显示全部」：仅 1080p 频道先弹一次确认
        if (safeMode && channel.safe == false && !bypassUnsafeConfirm) {
            _state.update { it.copy(pendingUnsafe = channel, switching = false) }
            return
        }
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
        prefetchNextPlaylist(channel)
    }

    /** 切台瞬间就把下一个频道的 playlist 写入缓存（体积 ~1KB，不抢首片带宽）。 */
    private fun prefetchNextPlaylist(current: LiveChannel) {
        val list = _state.value.channels
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == current.id }
        if (index < 0) return
        val next = list.getOrNull(index + 1) ?: return
        val url = if (safeMode) next.safeLine?.url ?: next.bestLine?.url else next.bestLine?.url
        if (url == null || !prefetchedPlaylists.add(url)) return
        scope.launch(Dispatchers.IO) {
            com.xingmu.tv.player.PlayerHttp.prewarm(appContext, url, fetchSegment = false)
        }
        // t25：下一个频道的 TLS 也一并预握手（连按 CH+ 时最先用到的就是它）
        scope.launch(Dispatchers.IO) {
            if (preconnectedHosts.add(hostOf(url) ?: url)) {
                val ms = com.xingmu.tv.player.PlayerHttp.preconnect(appContext, url)
                if (ms >= 0) TvLog.d(TAG, "next preconnect ${hostOf(url)} ${ms}ms")
            }
        }
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
     * 先 prepare 首选源，2.5s 未出首帧就切次优源（单实例约束下不能真并行双 prepare）：
     * 切档前先预热次优 URL，减少二次等待；首帧回调会 cancel 本任务。
     */
    private fun startSlowStartFallback(channel: LiveChannel, line: LiveLine) {
        slowStartJob?.cancel()
        val lower = channel.rankedLines.firstOrNull {
            it.autoPlayable && it.url !in attemptedUrls &&
                (line.displayHeight <= 0 || it.displayHeight in 1 until line.displayHeight)
        } ?: return
        slowStartJob = scope.launch {
            delay(SLOW_START_MS)
            val state = _state.value
            if (state.current?.id != channel.id || !state.switching) return@launch
            if (attemptedUrls.lastOrNull() != line.url) return@launch
            _state.update { it.copy(message = "首选源 2.5s 未就绪，已切次优源 ${lower.displayHeight}p") }
            degradedFrom = line.displayHeight
            warmUp(lower.url)
            playLine(channel, lower, "race")
        }
    }

    private fun warmUp(url: String) {
        scope.launch(Dispatchers.IO) {
            // 拉 playlist（≤256KB）写入 SimpleCache，再预取首个分片前 64KB（Range）
            com.xingmu.tv.player.PlayerHttp.prewarm(appContext, url)
        }
        // 并行做 TLS 预握手：playlist 预取走 SimpleCache 数据源，连接池不同，
        // 只预热首片不足以让播放器复用连接（P0-2 的真收益在这里）。
        scope.launch(Dispatchers.IO) {
            if (preconnectedHosts.add(hostOf(url) ?: url)) {
                val ms = com.xingmu.tv.player.PlayerHttp.preconnect(appContext, url)
                if (ms >= 0) TvLog.i(TAG, "preconnect ${hostOf(url)} ${ms}ms")
            }
        }
    }

    /**
     * t25：焦点停留时预连接 CH+/CH- 相邻频道（只握手，不拉 playlist——避免抢当前台带宽）。
     * 连切场景下 2 条相邻连接的 TLS 早在切台前就绪。
     */
    private fun preconnectNeighbors(current: LiveChannel) {
        val list = _state.value.channels
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == current.id }
        if (index < 0) return
        val neighbors = listOfNotNull(list.getOrNull(index - 1), list.getOrNull(index + 1))
        if (neighbors.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            neighbors.forEach { neighbor ->
                val url = if (safeMode) neighbor.safeLine?.url ?: neighbor.bestLine?.url
                else neighbor.bestLine?.url
                if (url != null && preconnectedHosts.add(hostOf(url) ?: url)) {
                    val ms = com.xingmu.tv.player.PlayerHttp.preconnect(appContext, url)
                    if (ms >= 0) TvLog.d(TAG, "neighbor preconnect ${hostOf(url)} ${ms}ms")
                }
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
        val current = now()
        // 安全模式优先用 live_safe.json 里那条（720>576>480），不再拿 1080p 硬试
        val safeLine = if (safeMode) channel.safeLine?.takeIf {
            (hostCooldownUntil[hostOf(it.url)] ?: 0L) <= current
        } else null
        val rest = channel.rankedLines.filter { line ->
            if (!line.autoPlayable) return@filter false
            if (safeLine != null && line.url == safeLine.url) return@filter false
            if ((hostCooldownUntil[hostOf(line.url)] ?: 0L) > current) return@filter false
            if (line.displayHeight < 2000) return@filter true
            prefer4K && caps.supportsLine(line.measuredCodec, line.displayHeight)
        }
        val withinCap = rest.filter { it.displayHeight <= cap || it.displayHeight <= 0 }
        val capped = if (withinCap.isNotEmpty()) withinCap else rest
        // 74.91 是已知慢 CDN：同档/更高档且 ttfb 更低的备选优先（仍限制在 ≤720 安全档）
        val fasterAlt = if (safeLine != null && hostOf(safeLine.url) == "74.91.26.218") {
            val safeLatency = safeLine.latencyMs
            capped.firstOrNull { alt ->
                hostOf(alt.url) != "74.91.26.218" &&
                    alt.displayHeight >= safeLine.displayHeight &&
                    alt.latencyMs != null && safeLatency != null &&
                    alt.latencyMs!! < safeLatency
            }
        } else {
            null
        }
        var ordered = if (safeLine != null) {
            listOfNotNull(fasterAlt, safeLine) + capped.filter { it.url != fasterAlt?.url }
        } else {
            capped
        }
        if (ordered.isEmpty()) {
            // 全被 host 熔断时不至于整台不可用：回退 safeLine + 原始候选
            ordered = listOfNotNull(channel.safeLine) + channel.rankedLines.filter { it.autoPlayable }
        }
        val ready = ordered.filter { (lineCooldownUntil[it.url] ?: 0L) <= current }
        // 全部在冷却时仍允许尝试，避免整台不可用（冷却只用于排序降权）
        return if (ready.isNotEmpty()) ready else ordered
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
        val host = hostOf(url) ?: return
        val hostFails = (hostFailures[host] ?: 0) + 1
        hostFailures[host] = hostFails
        if (hostFails >= 2) {
            hostFailures[host] = 0
            hostCooldownUntil[host] = now() + HOST_COOLDOWN_MS
            TvLog.w(TAG, "host circuit-open 30min: $host")
        }
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()

    /** 首帧后预取下一个安全频道（CH+/CH- 连切场景命中 playlist + 首片缓存）。 */
    private fun prefetchNeighbors(current: LiveChannel) {
        val list = _state.value.channels
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == current.id }
        if (index < 0) return
        val next = list.getOrNull(index + 1) ?: return
        val url = if (safeMode) {
            next.safeLine?.url ?: next.bestLine?.url
        } else {
            next.bestLine?.url
        } ?: return
        if (!prefetchedUrls.add(url)) return
        scope.launch(Dispatchers.IO) {
            // 等当前流稳定 0.5s 再预取（zap 间隔 1.8s，留足时间；避免和首片抢带宽）
            kotlinx.coroutines.delay(500)
            com.xingmu.tv.player.PlayerHttp.prewarm(appContext, url, fetchSegment = true)
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

    /** 10 分钟 rebuffer >3：降到更低一档安全源，并在 HUD 提示。 */
    private fun downgradeForRebuffer(count: Int) {
        val channel = _state.value.current ?: return
        val currentHeight = _state.value.currentLine?.displayHeight ?: _state.value.info.height
        val currentUrl = _state.value.currentLine?.url
        val options = candidates(channel).filter { it.url != currentUrl }
        val lower = options.firstOrNull {
            it.displayHeight in 1 until maxOf(currentHeight, 1)
        } ?: options.firstOrNull()
        if (lower == null) {
            _state.update { it.copy(message = "网络不稳（10 分钟 $count 次缓冲），暂无更低线路") }
            return
        }
        degradedFrom = currentHeight
        _state.update {
            it.copy(message = "网络不稳（10 分钟 $count 次缓冲），已降到 ${lower.displayHeight}p")
        }
        playLine(channel, lower, "rebuffer")
    }

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
        (player as? com.xingmu.tv.player.PlayerControllerImpl)?.setAllowHighQuality(enabled)
        applyQualityCap()
        // 安全模式随之开关：强制 1080 时不再过滤频道
        safeMode = safeDataLoaded && legacyMstarDevice && !enabled
        val visible = visibleChannels(allChannels)
        val (groups, grouped) = buildGroups(baseGroups, visible)
        val hidden = (allChannels.size - visible.size).coerceAtLeast(0)
        _state.update {
            it.copy(
                force1080 = enabled,
                safeMode = safeMode,
                channels = visible,
                groups = groups,
                groupedChannels = grouped,
                hiddenUnsafeCount = hidden,
                safeFilterHint = safeFilterHint(hidden),
                message = if (enabled) "已强制 1080p/4K（本机可能黑屏，仅调试用）" else "已回到 720p 安全模式",
            )
        }
        _state.value.current?.let { playChannel(it, resetHops = true) }
        TvLog.i(TAG, "force1080=$enabled safeMode=$safeMode")
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
    // 安全模式（live_safe.json）
    // ------------------------------------------------------------------

    private fun visibleChannels(full: List<LiveChannel>): List<LiveChannel> =
        if (safeMode && !showAllChannels) full.filter { it.safe != false } else full

    private fun safeFilterHint(hidden: Int): String? =
        if (safeMode && !showAllChannels && hidden > 0) {
            "本机已过滤 $hidden 个仅 1080p 的频道 · 按 MENU 查看全部"
        } else {
            null
        }

    /** 设置/ MENU：切换“仅安全频道 / 显示全部（可能黑屏）”。 */
    fun setShowAllChannels(enabled: Boolean) {
        if (!safeMode) return
        showAllChannels = enabled
        prefs.edit().putBoolean(KEY_SHOW_ALL_CHANNELS, enabled).apply()
        val visible = visibleChannels(allChannels)
        val (groups, grouped) = buildGroups(baseGroups, visible)
        val hidden = (allChannels.size - visible.size).coerceAtLeast(0)
        _state.update {
            it.copy(
                channels = visible,
                groups = groups,
                groupedChannels = grouped,
                showAllChannels = enabled,
                hiddenUnsafeCount = hidden,
                safeFilterHint = safeFilterHint(hidden),
                message = if (enabled) "已显示全部频道（1080p 可能黑屏）" else "已隐藏仅 1080p 频道",
            )
        }
        // 关闭显示全部时，若正在播 unsafe 频道，切回第一个安全频道
        if (!enabled) {
            val current = _state.value.current
            if (current != null && current.safe == false) {
                visible.firstOrNull { it.hasSafeSource }?.let { playChannel(it, resetHops = true) }
            }
        }
    }

    fun confirmUnsafePlay() {
        val channel = _state.value.pendingUnsafe ?: return
        _state.update { it.copy(pendingUnsafe = null) }
        playChannel(channel, resetHops = true, bypassUnsafeConfirm = true)
    }

    fun cancelUnsafePlay() {
        _state.update { it.copy(pendingUnsafe = null) }
    }

    private fun seedGroups(): List<LiveGroup> {
        val byId = LinkedHashMap<String, LiveGroup>()
        allChannels.forEach { channel ->
            byId.getOrPut(channel.groupId) {
                LiveGroup(channel.groupId, channel.groupName, channel.order)
            }
        }
        return byId.values.sortedBy { it.order }
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
            is PlayerEvent.RebufferBurst -> downgradeForRebuffer(event.count)
            is PlayerEvent.VodSlowStart -> Unit
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
        // t30：「最近」按首帧成功记录（不是按焦点），避免划过一堆台把最近刷乱
        channelPrefs.touchRecent(channel.id)
        prefetchNeighbors(channel)
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
        val lineUrl = attemptedUrls.lastOrNull() ?: st.currentLine?.url
        val host = com.xingmu.tv.player.PlayerHttp.hostOf(lineUrl)
        val cached = if (com.xingmu.tv.player.PlayerHttp.wasWarmed(lineUrl)) 1 else 0
        // qa 验收埋点（PLAYER_PERF §6）：host/cached 用于区分慢源 vs 预热失效
        TvLog.i(
            "PLAYER_EVT",
            "action=zap from=$zapFrom to=${channel.name} t_prepare_ms=$lastPrepareMs " +
                "t_first_frame_ms=$latency src=$src rebuffer=$rebuffer host=$host cached=$cached " +
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

    /** t30：长按 OK 收藏 / 取消收藏（按频道 id，跨重启保留）。 */
    fun toggleFavorite(channel: LiveChannel) {
        val added = channelPrefs.toggleFavorite(channel.id)
        _state.update {
            it.copy(message = if (added) "已收藏：${channel.name}" else "已取消收藏：${channel.name}")
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
                if (safeMode && !showAllChannels) {
                    setShowAllChannels(true)
                }
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
        const val SLOW_START_MS = 2_500L      // 首选源 2.5s 未 READY → 切次优源（竞速兜底）
        const val FOCUS_WARM_MS = 250L        // 焦点停留 ≥250ms 预热 playlist + 首片 64KB
        const val MAX_SOURCE_FAILS = 3        // 同源连续失败 3 次 → degraded 冷却
        const val SOURCE_COOLDOWN_MS = 10 * 60 * 1000L
        const val HOST_COOLDOWN_MS = 30 * 60 * 1000L
        const val MAX_EPG_URLS = 3
        const val EPG_REFRESH_MS = 30L * 60 * 1000
        const val EPG_REFRESH_MINUTES = 30
        const val KEY_LAST_CHANNEL = "last_channel_id"
        const val KEY_PREFER_4K = "prefer_4k"
        const val KEY_FORCE_1080 = "force_1080"
        const val KEY_SHOW_ALL_CHANNELS = "show_all_channels"
    }
}
