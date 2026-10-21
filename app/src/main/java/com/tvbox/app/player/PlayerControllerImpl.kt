package com.tvbox.app.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.tvbox.app.core.TvLog
import com.tvbox.app.data.MediaUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 播放器内核（Media3 ExoPlayer 1.5.1 单实例）。
 *
 * 生命周期：由播放页持有 → `remember { PlayerControllerImpl(context) }` → `release()`。
 * 绝对不并存第二个 ExoPlayer（1GB 盒子 codec 初始化会失败，PLAN_ARCHITECTURE §7.6）。
 * 线程：所有公开方法可任意线程调用，内部统一切到主线程；ExoPlayer 创建在主 Looper。
 *
 * 音频/视频参数落点见 docs/PLAYER_IMPL.md 与 docs/AVSYNC_SPEC.md。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerControllerImpl(
    context: Context,
) : PlayerController, PlayerEventSource, PlaybackInfoSource, PlayerProfileControl, PlayerLiveOps,
    PlaybackStatsSource {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var loadControl: SwitchingLoadControl? = null
    private var dataSourceFactory: PlaybackDataSourceFactory? = null
    private var audioSink: CalibrationAudioSink? = null
    private var released = false

    private val _state = MutableStateFlow<PlayerController.State>(PlayerController.State.Idle)
    private val _position = MutableStateFlow(0L)
    private val _duration = MutableStateFlow(0L)
    private val _info = MutableStateFlow(PlaybackInfo())
    private val _events = MutableSharedFlow<PlayerEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _stats = MutableStateFlow(PlaybackStats())
    private val _generation = MutableStateFlow(0)

    override val state: StateFlow<PlayerController.State> = _state.asStateFlow()
    override val positionMs: StateFlow<Long> = _position.asStateFlow()
    override val durationMs: StateFlow<Long> = _duration.asStateFlow()
    override val playbackInfo: StateFlow<PlaybackInfo> = _info.asStateFlow()
    override val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    override val stats: StateFlow<PlaybackStats> = _stats.asStateFlow()
    /** 播放器重建代数：VideoSurface 监听它，重建后重新绑 Surface。 */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    // ---- 当前会话状态 ----
    @Volatile
    private var qualityHeight: Int = 0
    @Volatile
    private var liveMode: Boolean = false
    @Volatile
    private var aspectMode: AspectMode = AspectMode.FIT
    @Volatile
    private var videoOffsetMs: Int = 0

    /** 调试开关：强制放开 >720p（X4-50 上可能黑屏）。 */
    @Volatile
    private var allowHighQuality = false

    /** 全局 720p 硬上限：API≤23 或存在 OMX.MS.* 解码器（真机验证 >720p 纹理路径整机不可用）。 */
    private val legacyHighResBroken: Boolean
        get() = android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.M ||
            DeviceCapabilities.caps.hasMstarDecoder

    /** 所有 setQuality/轨道参数都走这里：默认把 >720p 请求压到 720p。 */
    private fun effectiveHeight(requested: Int): Int {
        val base = if (requested <= 0) 1080 else requested
        return if (!allowHighQuality && legacyHighResBroken) minOf(base, 720) else base
    }

    private var lastMedia: MediaUrl? = null
    private var fallbackSources: List<MediaUrl> = emptyList()
    private var fallbackCursor = 0
    private var surfaceView: android.view.SurfaceView? = null
    private var textureView: android.view.TextureView? = null
    private var lastTextureUpdateAt = 0L
    private var playStartedAt = 0L
    private var bufferingSince = 0L
    private var firstFrame = false
    private var noDataFired = false
    private var decoderRebuilt = false
    private var prepareMs = 0L
    private var hasPlayed = false
    private var lastRebufferDowngradeAt = 0L

    private var tickerJob: Job? = null
    private var watchdogJob: Job? = null

    // ---- 自愈采样窗口 ----
    private val underrunTimes = ArrayDeque<Long>()
    private val rebufferTimes = ArrayDeque<Long>()
    private val rebufferLongTimes = ArrayDeque<Long>()
    private var lastRebufferBurstAt = 0L
    private var lastStatsLogAt = 0L
    private var lastEscalationAt = 0L
    private var droppedAccum = 0L
    private var droppedElapsedMs = 0L
    private var droppedWindowStart = 0L
    private var lastDowngradeAt = 0L
    private var currentFps = 30f
    private var videoFormat: Format? = null
    private var lastResyncAt = 0L

    /** 播放器是否已释放（UI 在导航动画/重组尾部可能晚于 release 触发一次绑定）。 */
    val isReleased: Boolean get() = released

    /** 供 VideoSurface（同 module）绑定 PlayerView；外部请用 VideoSurface。 */
    @get:UnstableApi
    val exoPlayer: ExoPlayer
        get() = ensurePlayer() ?: error("PlayerControllerImpl 已 release")

    // ------------------------------------------------------------------
    // 契约实现
    // ------------------------------------------------------------------

    override fun play(media: MediaUrl, startPositionMs: Long) {
        onMain {
            if (released) return@onMain
            val p = ensurePlayer() ?: return@onMain
            lastMedia = media
            playStartedAt = now()
            bufferingSince = playStartedAt
            firstFrame = false
            noDataFired = false
            decoderRebuilt = false
            prepareMs = 0L
            hasPlayed = false
            lastRebufferDowngradeAt = 0L
            lastRebufferBurstAt = 0L
            lastStatsLogAt = 0L
            rebufferTimes.clear()
            rebufferLongTimes.clear()
            resetSelfHealWindows()
            // 新起播恢复默认缓冲档位（欠载升级只针对当前这一条流）
            loadControl?.reset()
            // 倍速只对当前播放生效，新起播一律回到 1.0（PLAYBACK_SPEC §4）
            runCatching { p.playbackParameters = PlaybackParameters(1f, 1f) }
            dataSourceFactory?.setHeaders(media.headers)
            // 硬要求：切集/换源必须 stop → setMediaItem → prepare，禁止在旧时间轴上换源
            p.stop()
            val item = buildMediaItem(media)
            if (liveMode && startPositionMs <= 0L) {
                // 直播默认从 live edge 起播（默认位置已包含 targetOffsetMs），不能 seek 到窗口 0
                p.setMediaItem(item)
            } else {
                p.setMediaItem(item, startPositionMs.coerceAtLeast(0L))
            }
            p.prepare()
            p.playWhenReady = true
            // MStar 老平台切源时 GL 纹理可能瞬时失效：300ms 后用同一个 SurfaceView 重新绑定一次
            reattachSurfaceSoon()
            _state.value = PlayerController.State.Buffering
            _position.value = startPositionMs.coerceAtLeast(0L)
            updateInfo()
            startWatchdog(media.url)
            TvLog.i(TAG, "play url=${media.url} start=${startPositionMs}ms live=$liveMode")
        }
    }

    override fun pause() {
        onMain {
            player?.pause()
            if (_state.value !is PlayerController.State.Error) _state.value = PlayerController.State.Paused
        }
    }

    override fun resume() {
        onMain {
            val p = player ?: return@onMain
            p.play()
            _state.value = if (p.isPlaying) PlayerController.State.Playing else PlayerController.State.Buffering
        }
    }

    override fun seekTo(positionMs: Long) {
        onMain {
            val p = player ?: return@onMain
            val dur = p.duration
            val upper = if (dur == C.TIME_UNSET || dur < 0) Long.MAX_VALUE else dur
            val target = positionMs.coerceIn(0L, upper)
            p.seekTo(target)
            _position.value = target
        }
    }

    override fun setSpeed(speed: Float) {
        onMain {
            if (liveMode || _info.value.isLive) {
                _info.update { it.copy(speed = 1f) }
                emit(PlayerEvent.Message("直播不支持倍速（会造成音画漂移）"))
                return@onMain
            }
            val target = PlayerTuning.SPEEDS.minByOrNull { abs(it - speed) } ?: 1f
            // 走 Sonic（AudioProcessor），不改 AudioTrack playbackParams；pitch 固定 1.0 保持音调
            player?.playbackParameters = PlaybackParameters(target, 1f)
            _info.update { it.copy(speed = target) }
        }
    }

    override fun setQuality(maxHeight: Int) {
        onMain {
            val normalized = when (maxHeight) {
                480, 720, 1080, 2160 -> maxHeight
                else -> 0
            }
            val effective = effectiveHeight(normalized)
            if (qualityHeight == effective) return@onMain
            qualityHeight = effective
            trackSelector?.setParameters(buildTrackParameters(effective))
            _info.update { it.copy(qualityHeight = effective) }
            TvLog.i(
                TAG,
                "quality -> ${QualityOptions.label(effective)} (request=${QualityOptions.label(normalized)} " +
                    "legacy720=${!allowHighQuality && legacyHighResBroken}, trackSelector only)",
            )
        }
    }

    /**
     * 调试开关「强制 1080/4K」：默认关。X4-50 上 >720p 解码/纹理路径整机不可用，
     * 打开后可能出现黑屏，仅用于调试/换机验证。
     */
    fun setAllowHighQuality(enabled: Boolean) {
        onMain {
            allowHighQuality = enabled
            trackSelector?.setParameters(buildTrackParameters(qualityHeight))
            _info.update { it.copy(qualityHeight = effectiveHeight(qualityHeight)) }
            emit(PlayerEvent.Message(if (enabled) "已放开高清上限（本机可能黑屏）" else "已恢复 720p 全局上限"))
        }
    }

    fun isHighQualityAllowed(): Boolean = allowHighQuality

    override fun setAudioDelayMs(delayMs: Int) {
        videoOffsetMs = clampVideoOffset(delayMs)
        audioSink?.applyVideoOffsetMs(videoOffsetMs)
        _info.update { it.copy(videoOffsetMs = videoOffsetMs) }
    }

    override fun release() {
        onMain {
            if (released) return@onMain
            released = true
            watchdogJob?.cancel()
            watchdogJob = null
            tickerJob?.cancel()
            tickerJob = null
            player?.let {
                it.removeListener(playerListener)
                it.removeAnalyticsListener(analyticsListener)
                runCatching { it.release() }
            }
            player = null
            audioSink = null
            trackSelector = null
            loadControl = null
            dataSourceFactory = null
            _state.value = PlayerController.State.Idle
            _position.value = 0L
            _duration.value = 0L
            _info.value = PlaybackInfo()
            scope.cancel()
            TvLog.i(TAG, "released")
        }
    }

    // ------------------------------------------------------------------
    // 增强接口
    // ------------------------------------------------------------------

    override fun setLiveMode(live: Boolean) {
        liveMode = live
        loadControl?.setLive(live)
        if (!live) {
            runCatching { player?.playbackParameters = PlaybackParameters(1f, 1f) }
            _info.update { it.copy(speed = 1f, isLive = player?.isCurrentMediaItemLive == true) }
        } else {
            _info.update { it.copy(isLive = true, speed = 1f, dvrWindowMs = 0L, canSeekBack = false) }
        }
        TvLog.i(TAG, "liveMode=$live")
    }

    override fun isLiveMode(): Boolean = liveMode || _info.value.isLive

    fun aspectMode(): AspectMode = aspectMode

    fun setAspectMode(mode: AspectMode) {
        aspectMode = mode
        _info.update { it.copy(aspect = mode) }
        TvLog.i(TAG, "aspect=${mode.label}")
    }

    fun seekBy(deltaMs: Long) {
        seekTo(_position.value + deltaMs)
    }

    /**
     * t22 快切线路：调用方把当前集的所有线路（不同 playFrom）按顺序传入，
     * 首片 4s 未到时自动试下一条并在 HUD 提示。
     */
    fun setFallbackSources(sources: List<MediaUrl>) {
        fallbackSources = sources
        fallbackCursor = 0
    }

    private fun tryNextVodLine(): Boolean {
        val next = fallbackSources.getOrNull(fallbackCursor + 1) ?: return false
        fallbackCursor++
        TvLog.i(TAG, "VOD first slice 4s timeout -> switch line: ${next.url.take(100)}")
        emit(PlayerEvent.Message("首片 4s 未到，已切换线路"))
        play(next, _position.value)
        return true
    }

    /** VideoSurface 只创建一次并注册 SurfaceView；切源时复用同一个 Surface，不做 detach/attach。 */
    fun bindSurfaceView(view: android.view.SurfaceView?) {
        surfaceView = view
        onMain {
            if (view != null && !released) player?.setVideoSurfaceView(view)
        }
    }

    private fun reattachSurfaceSoon() {
        val view = surfaceView ?: return
        mainHandler.postDelayed(
            {
                if (!released) {
                    surfaceView?.let { player?.setVideoSurfaceView(it) }
                }
            },
            300L,
        )
    }

    /** API≤23 的 TextureView 路径：绑定后由 onSurfaceTextureUpdated 刷新时间戳。 */
    fun bindTextureView(view: android.view.TextureView?) {
        textureView = view
        lastTextureUpdateAt = now()
        onMain {
            if (view != null && !released) player?.setVideoTextureView(view)
        }
    }

    fun notifyTextureUpdated() {
        lastTextureUpdateAt = now()
    }

    /**
     * 黑屏检测：`onRenderedFirstFrame` 只代表解码器吐了帧，不代表 GL 合成可见。
     * 1080p 在 MStar 上可能持续 GL 0x500：首帧后 3s 内没有一次 texture 更新 → 判黑屏并请求换源。
     */
    private fun scheduleBlackScreenCheck() {
        if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.M) return
        // 只在直播 1080p 上启用：B 站 mp4 等路径 TextureView 不回调 onSurfaceTextureUpdated，
        // 且 getBitmap 在部分平台恒黑，容易把正常画面误判（真机已复现）。
        if (!liveMode || _info.value.height < 1080) return
        val view = textureView ?: return
        val checkAt = now()
        mainHandler.postDelayed(
            {
                if (released || !firstFrame) return@postDelayed
                val last = lastTextureUpdateAt
                // checkAt 之后有过更新；或最近 2.5s 内更新过 → 正常
                if (last >= checkAt || now() - last < 2_500L) return@postDelayed
                // TextureView 在部分平台不回调 onSurfaceTextureUpdated（B 站 mp4 实测），
                // 用一次 32×32 采样确认是否真的全黑，避免误杀正常画面。
                val bitmap = runCatching { view.getBitmap(32, 32) }.getOrNull()
                val hasVisiblePixel = bitmap != null && run {
                    var visible = false
                    for (y in 0 until bitmap.height step 2) {
                        for (x in 0 until bitmap.width step 2) {
                            val color = bitmap.getPixel(x, y)
                            if (android.graphics.Color.alpha(color) > 8 &&
                                (android.graphics.Color.red(color) + android.graphics.Color.green(color) +
                                    android.graphics.Color.blue(color)) > 24
                            ) {
                                visible = true
                                break
                            }
                        }
                        if (visible) break
                    }
                    visible
                }
                if (hasVisiblePixel) {
                    lastTextureUpdateAt = now()
                    return@postDelayed
                }
                TvLog.e(
                    TAG,
                    "BLACK_SCREEN detected: no texture update for ${now() - last}ms and sampled bitmap is black " +
                        "url=${lastMedia?.url}",
                )
                emit(PlayerEvent.Message("画面未渲染，正在切换备用线路"))
                emit(PlayerEvent.NeedsFallback("E_BLACK_SCREEN", "1080p 黑屏（GL）", lastMedia?.url))
            },
            3_000L,
        )
    }

    override fun dvrWindowMs(): Long {
        val p = player ?: return 0L
        if (!p.isCurrentMediaItemLive) return 0L
        val duration = p.duration
        return if (duration == C.TIME_UNSET || duration <= 0L) 0L else duration
    }

    override fun isDvrSeekable(): Boolean {
        val p = player ?: return false
        return p.isCurrentMediaItemLive && p.isCurrentMediaItemSeekable &&
            dvrWindowMs() >= PlayerTuning.LIVE_DVR_MIN_WINDOW_MS
    }

    override fun seekToLiveEdge() {
        onMain {
            val p = player ?: return@onMain
            if (p.isCurrentMediaItemLive) {
                p.seekToDefaultPosition(p.currentMediaItemIndex)
            } else {
                seekTo(_duration.value)
            }
        }
    }

    override fun seekBack(seconds: Int): Boolean {
        if (!isDvrSeekable()) {
            if (_info.value.isLive || liveMode) emit(PlayerEvent.Message("该频道不支持回看"))
            return false
        }
        val window = dvrWindowMs()
        val target = (_position.value - seconds * 1000L).coerceIn(0L, window)
        seekTo(target)
        return true
    }

    override fun seekForward(seconds: Int): Boolean {
        if (!isDvrSeekable()) {
            if (_info.value.isLive || liveMode) emit(PlayerEvent.Message("直播只能回退，不能快进"))
            return false
        }
        seekTo(_position.value + seconds * 1000L)
        return true
    }

    /** 直播/点播通用的“回到 1.0x + 缓冲档位复位”，切台前调用。 */
    fun resetForSourceSwitch() {
        onMain {
            resetSelfHealWindows()
            loadControl?.reset()
            runCatching { player?.playbackParameters = PlaybackParameters(1f, 1f) }
        }
    }

    // ------------------------------------------------------------------
    // ExoPlayer 构建
    // ------------------------------------------------------------------

    private fun ensurePlayer(): ExoPlayer? {
        if (released) return null
        player?.let { return it }
        check(Looper.myLooper() == Looper.getMainLooper()) { "ExoPlayer 必须在主线程创建" }

        val selector = DefaultTrackSelector(appContext).apply {
            setParameters(buildTrackParameters(qualityHeight))
        }
        val control = SwitchingLoadControl(liveMode)
        val dataSource = createPlaybackDataSourceFactory(appContext)
        val renderers = TvRenderersFactory(appContext) { sink ->
            audioSink = sink
            sink.applyVideoOffsetMs(videoOffsetMs)
        }

        val p = ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(HlsFirstMediaSourceFactory(dataSource))
            .setTrackSelector(selector)
            .setLoadControl(control)
            // 1s 分片下 CLOSEST_SYNC 几乎瞬时，精确 seek 反而慢且易卡
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .setSeekBackIncrementMs(PlayerTuning.SEEK_STEP_MS)
            .setSeekForwardIncrementMs(PlayerTuning.SEEK_STEP_MS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // 跳静音会改时间轴节奏（AVSYNC_SPEC §2）
            .setSkipSilenceEnabled(false)
            // 电视上匹配帧率会造成黑屏闪断，低端设备直接关
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .setUseLazyPreparation(true)
            .build()

        p.addListener(playerListener)
        p.addAnalyticsListener(analyticsListener)
        p.trackSelectionParameters = buildTrackParameters(qualityHeight)
        player = p
        trackSelector = selector
        loadControl = control
        dataSourceFactory = dataSource
        _generation.update { it + 1 }
        startTicker()
        TvLog.i(TAG, "player created #${_generation.value} quality=${QualityOptions.label(qualityHeight)} live=$liveMode")
        return p
    }

    /**
     * 轨道策略（AVSYNC_SPEC §1）：
     * 1080p30 / 6Mbps / H.264 优先；禁混合 mime 与非无缝自适应；超上限的轨道不选。
     */
    private fun buildTrackParameters(maxHeight: Int): DefaultTrackSelector.Parameters {
        val effective = effectiveHeight(maxHeight)
        val (width, height) = QualityOptions.maxSize(effective)
        return DefaultTrackSelector.Parameters.Builder(appContext)
            .setMaxVideoSize(width, height)
            .setMaxVideoFrameRate(PlayerTuning.MAX_VIDEO_FRAME_RATE)
            .setMaxVideoBitrate(QualityOptions.maxBitrate(effective))
            .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
            .setAllowVideoMixedMimeTypeAdaptiveness(false)
            .setAllowVideoNonSeamlessAdaptiveness(false)
            .setAllowVideoMixedDecoderSupportAdaptiveness(false)
            .setAllowAudioMixedMimeTypeAdaptiveness(false)
            .setAllowAudioMixedDecoderSupportAdaptiveness(false)
            // 单码率私有源（如 B 站 mp4 只有 1024p）无 ≤720 轨道时仍允许播放，避免“无轨道”直接失败；
            // 若画面黑，由 BLACK_SCREEN 检测触发换源/降 qn。
            .setExceedVideoConstraintsIfNecessary(true)
            .setTunnelingEnabled(false)
            .build()
    }

    private fun buildMediaItem(media: MediaUrl): MediaItem {
        val noQuery = media.url.substringBefore('?').lowercase()
        val looksProgressive = noQuery.endsWith(".mp4") || noQuery.endsWith(".flv") ||
            noQuery.endsWith(".ts") || noQuery.endsWith(".mpd") || noQuery.endsWith(".mkv") ||
            noQuery.endsWith(".mp3") || noQuery.endsWith(".aac") || noQuery.endsWith(".ogg") ||
            noQuery.endsWith(".oga") || noQuery.endsWith(".m4a") || noQuery.endsWith(".flac") ||
            noQuery.endsWith(".wav")
        val looksHls = noQuery.contains(".m3u8") || !looksProgressive
        // 直播频道绝大多数是 “/live.php?id=xxx” 这类无扩展名/伪扩展名地址，按 HLS 解析
        val forceHls = liveMode && looksHls
        return MediaItem.Builder()
            .setUri(media.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(media.label).build())
            .apply { if (forceHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
    }

    // ------------------------------------------------------------------
    // 状态采样
    // ------------------------------------------------------------------

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                val p = player
                if (p != null) {
                    _position.value = p.currentPosition.coerceAtLeast(0L)
                    val duration = p.duration
                    _duration.value = if (duration == C.TIME_UNSET || duration < 0) 0L else duration
                    if (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING) {
                        updateInfo()
                    }
                    // 30s 一次统计埋点：qa 10 分钟 rebuffer 窗口取最后一条 action=stats
                    val t = now()
                    if (liveMode && t - lastStatsLogAt >= 30_000L) {
                        lastStatsLogAt = t
                        val s = _stats.value
                        TvLog.i(
                            "PLAYER_EVT",
                            "action=stats rebuffer=${s.rebufferCount} dropped=${s.droppedFrames} " +
                                "underrun=${s.audioUnderruns} bufferEscalations=${s.bufferEscalations} " +
                                "pos=${_position.value} host=${PlayerHttp.hostOf(lastMedia?.url)}",
                        )
                    }
                }
                delay(500)
            }
        }
    }

    private fun updateInfo() {
        val p = player ?: return
        var width = 0
        var height = 0
        var bitrate = 0
        var fps = 0f
        var videoMime: String? = null
        var audioMime: String? = null
        var channels = 0
        for (group in p.currentTracks.groups) {
            if (!group.isSelected || group.length <= 0) continue
            val format = runCatching { group.getTrackFormat(0) }.getOrNull() ?: continue
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    width = format.width
                    height = format.height
                    bitrate = format.bitrate
                    fps = format.frameRate
                    videoMime = format.sampleMimeType
                    if (fps > 0f) currentFps = fps
                }
                C.TRACK_TYPE_AUDIO -> {
                    audioMime = format.sampleMimeType
                    channels = format.channelCount
                }
            }
        }
        val liveNow = p.isCurrentMediaItemLive
        val liveDuration = if (liveNow) {
            val d = p.duration
            if (d == C.TIME_UNSET || d <= 0L) 0L else d
        } else {
            0L
        }
        val canSeekBack = liveNow && p.isCurrentMediaItemSeekable &&
            liveDuration >= PlayerTuning.LIVE_DVR_MIN_WINDOW_MS
        _info.value = _info.value.copy(
            isLive = liveNow || (liveMode && p.mediaItemCount > 0),
            dvrWindowMs = liveDuration,
            canSeekBack = canSeekBack,
            width = width,
            height = height,
            videoBitrate = bitrate,
            frameRate = fps,
            videoMime = videoMime,
            audioMime = audioMime,
            audioChannels = channels,
            qualityHeight = qualityHeight,
            speed = p.playbackParameters.speed,
            videoOffsetMs = videoOffsetMs,
            aspect = aspectMode,
        )
        if (liveNow && liveDuration > 0) {
            // 直播窗口随分片滚动，进度条用它判断“可回退多久”
            _duration.value = liveDuration
        }
    }

    // ------------------------------------------------------------------
    // 8s 无数据看门狗
    // ------------------------------------------------------------------

    /** 直播首帧 5s；VOD 首片 4s 未到就切下一线路（t22）。 */
    private val firstFrameTimeoutMs: Long
        get() = if (liveMode) 5_000L else 4_000L

    private val noDataTimeoutMs: Long
        get() = if (liveMode) 4_000L else PlayerTuning.NO_DATA_TIMEOUT_MS

    /** 播放中“无位置进度”用更宽的 6s（HLS 直播 position 每秒跳动，但低端机可能丢 tick）。 */
    private val progressTimeoutMs: Long
        get() = if (liveMode) 6_000L else PlayerTuning.NO_DATA_TIMEOUT_MS

    private fun startWatchdog(url: String) {        watchdogJob?.cancel()
        noDataFired = false
        watchdogJob = scope.launch {
            var lastPos = -1L
            var lastProgressAt = now()
            while (isActive) {
                delay(1_000)
                val p = player ?: break
                val current = now()
                val pos = p.currentPosition
                val ready = p.playbackState == Player.STATE_READY
                val buffering = p.playbackState == Player.STATE_BUFFERING
                val wantsPlay = p.playWhenReady

                if (!firstFrame && current - playStartedAt >= firstFrameTimeoutMs && !noDataFired) {
                    noDataFired = true
                    TvLog.w(TAG, "watchdog: first-frame timeout ${current - playStartedAt}ms url=$url")
                    emit(PlayerEvent.NoData(current - playStartedAt, url))
                    if (!liveMode && tryNextVodLine()) {
                        break
                    }
                    if (!liveMode) emit(PlayerEvent.VodSlowStart(url))
                    emit(PlayerEvent.NeedsFallback("E_NO_DATA", "起播超时无画面", url))
                    break
                }
                // 首帧后只看“位置是否推进”。不再用 bufferingSince 判定：切源瞬间的 BUFFERING
                // 很容易把已出帧的流误判成卡死（真机误降级 bug 的根因）。
                if (firstFrame && ready && wantsPlay) {
                    if (pos != lastPos) {
                        lastPos = pos
                        lastProgressAt = current
                    } else if (current - lastProgressAt >= progressTimeoutMs && !noDataFired) {
                        noDataFired = true
                        TvLog.w(TAG, "watchdog: no progress ${current - lastProgressAt}ms url=$url")
                        emit(PlayerEvent.NoData(current - lastProgressAt, url))
                        emit(PlayerEvent.NeedsFallback("E_NO_DATA", "播放无进度", url))
                        break
                    }
                } else {
                    lastPos = pos
                    lastProgressAt = current
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 监听与自愈
    // ------------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    bufferingSince = now()
                    if (hasPlayed) {
                        val t = bufferingSince
                        rebufferTimes.addLast(t)
                        rebufferLongTimes.addLast(t)
                        while (rebufferLongTimes.isNotEmpty() &&
                            t - rebufferLongTimes.first() > REBUFFER_WINDOW_MS
                        ) {
                            rebufferLongTimes.removeFirst()
                        }
                        if (rebufferLongTimes.size > REBUFFER_BURST_THRESHOLD &&
                            t - lastRebufferBurstAt > 60_000L
                        ) {
                            lastRebufferBurstAt = t
                            TvLog.w(
                                TAG,
                                "rebuffer burst: ${rebufferLongTimes.size} in 10min -> downgrade line",
                            )
                            emit(PlayerEvent.RebufferBurst(rebufferLongTimes.size, REBUFFER_WINDOW_MS))
                        }
                        while (rebufferTimes.isNotEmpty() &&
                            t - rebufferTimes.first() > PlayerTuning.UNDERRUN_WINDOW_MS
                        ) {
                            rebufferTimes.removeFirst()
                        }
                        _stats.update { it.copy(rebufferCount = it.rebufferCount + 1) }
                        // 刚起播 5s 内的缓冲属于正常起播/切台，不计入降级
                        if (t - playStartedAt < 5_000L) return@onPlaybackStateChanged
                        // PLAYER_PERF §5：30s 内 rebuffer ≥2 次 → 降 720p
                        if (rebufferTimes.size >= 2 &&
                            t - lastRebufferDowngradeAt > PlayerTuning.UNDERRUN_WINDOW_MS
                        ) {
                            lastRebufferDowngradeAt = t
                            val current = if (qualityHeight == 0) _info.value.height else qualityHeight
                            if (current > 720 || qualityHeight == 0) {
                                setQuality(720)
                                emit(PlayerEvent.Message("网络不稳，已切 720p"))
                            }
                        }
                    }
                    if (_state.value !is PlayerController.State.Error) {
                        _state.value = PlayerController.State.Buffering
                    }
                }
                Player.STATE_READY -> {
                    bufferingSince = 0L
                    if (prepareMs == 0L && playStartedAt > 0L) {
                        prepareMs = (now() - playStartedAt).coerceAtLeast(0L)
                        emit(PlayerEvent.Prepared(prepareMs))
                    }
                    _state.value = if (player?.isPlaying == true) {
                        PlayerController.State.Playing
                    } else {
                        PlayerController.State.Ready
                    }
                    updateInfo()
                }
                Player.STATE_ENDED -> {
                    _state.value = PlayerController.State.Ended
                    _position.value = _duration.value
                }
                Player.STATE_IDLE -> {
                    if (_state.value !is PlayerController.State.Error) {
                        _state.value = PlayerController.State.Idle
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                _state.value = PlayerController.State.Playing
            } else if (player?.playbackState == Player.STATE_READY &&
                _state.value !is PlayerController.State.Error
            ) {
                _state.value = PlayerController.State.Paused
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateInfo()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _info.update { it.copy(speed = playbackParameters.speed) }
        }
    }

    private val analyticsListener = object : AnalyticsListener {

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            if (firstFrame) return
            firstFrame = true
            hasPlayed = true
            val elapsed = now() - playStartedAt
            if (prepareMs == 0L) prepareMs = elapsed.coerceAtLeast(0L)
            _stats.update { it.copy(firstFrameMs = elapsed) }
            // 首帧后重启看门狗：4s 无进度从“首帧时刻”开始计算，避免把起播慢误判成卡死
            startWatchdog(lastMedia?.url.orEmpty())
            _state.value = if (player?.isPlaying == true) {
                PlayerController.State.Playing
            } else {
                PlayerController.State.Ready
            }
            val src = if (_info.value.height > 0) "${_info.value.height}p" else "auto"
            val url = lastMedia?.url
            val host = PlayerHttp.hostOf(url)
            val cached = if (PlayerHttp.wasWarmed(url)) 1 else 0
            TvLog.i(
                "PLAYER_EVT",
                "action=play label=${lastMedia?.label ?: "-"} url=${url ?: "-"} " +
                    "t_prepare_ms=$prepareMs t_first_frame_ms=$elapsed src=$src " +
                    "rebuffer=${_stats.value.rebufferCount} host=$host cached=$cached",
            )
            emit(PlayerEvent.FirstFrame)
            scheduleBlackScreenCheck()
            TvLog.i(TAG, "first frame in ${elapsed}ms")
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            _info.update { it.copy(decoderName = decoderName) }
            TvLog.i(TAG, "decoder=$decoderName init=${initializationDurationMs}ms")
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            handleVideoFormatChanged(format)
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            handleAudioUnderrun(bufferSizeMs, elapsedSinceLastFeedMs)
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            handleDroppedFrames(droppedFrames, elapsedMs)
        }
    }

    private fun handleVideoFormatChanged(format: Format) {
        val previous = videoFormat
        videoFormat = format
        if (format.frameRate > 0f) currentFps = format.frameRate
        _info.update {
            it.copy(
                width = format.width,
                height = format.height,
                videoBitrate = format.bitrate,
                frameRate = format.frameRate,
                videoMime = format.sampleMimeType,
            )
        }
        if (previous == null || previous.width <= 0 || previous.height <= 0) return
        val aspectOld = previous.width.toFloat() / previous.height
        val aspectNew = if (format.height > 0) format.width.toFloat() / format.height else aspectOld
        val fpsJump = abs(previous.frameRate - format.frameRate) > 10f
        val aspectJump = abs(aspectOld - aspectNew) > 0.10f
        if (!fpsJump && !aspectJump) return
        val current = now()
        if (current - lastResyncAt < PlayerTuning.RESYNC_COOLDOWN_MS) return
        lastResyncAt = current
        val position = player?.currentPosition ?: return
        player?.seekTo(position)
        emit(PlayerEvent.Resynced(position))
        TvLog.w(TAG, "video format jump -> resync at ${position}ms (fps=$fpsJump aspect=$aspectJump)")
    }

    private fun handleAudioUnderrun(bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        val current = now()
        _stats.update { it.copy(audioUnderruns = it.audioUnderruns + 1) }
        TvLog.w(TAG, "audio underrun buffer=${bufferSizeMs}ms idle=${elapsedSinceLastFeedMs}ms")
        underrunTimes.addLast(current)
        while (underrunTimes.isNotEmpty() && current - underrunTimes.first() > PlayerTuning.UNDERRUN_WINDOW_MS) {
            underrunTimes.removeFirst()
        }
        if (underrunTimes.size < PlayerTuning.UNDERRUN_THRESHOLD) return
        if (current - lastEscalationAt < PlayerTuning.UNDERRUN_WINDOW_MS) return
        lastEscalationAt = current
        underrunTimes.clear()

        val level = _stats.value.bufferEscalations + 1
        loadControl?.raiseBuffer()
        _stats.update { it.copy(bufferEscalations = level) }

        var downgraded = false
        if (level >= 2) {
            val from = if (qualityHeight == 0) _info.value.height else qualityHeight
            if (from > 720 || qualityHeight == 0) {
                setQuality(720)
                _stats.update { it.copy(qualityDowngrades = it.qualityDowngrades + 1) }
                downgraded = true
            }
        }
        emit(PlayerEvent.BufferEscalated(level, liveMode, downgraded))
        emit(
            PlayerEvent.Message(
                if (downgraded) "音频欠载频繁，已降画质到 720p" else "音频缓冲不足，已提高缓冲",
            ),
        )
    }

    private fun handleDroppedFrames(droppedFrames: Int, elapsedMs: Long) {
        if (elapsedMs <= 0L || droppedFrames <= 0) return
        val current = now()
        if (droppedWindowStart == 0L || current - droppedWindowStart > PlayerTuning.DROPPED_FRAME_WINDOW_MS) {
            droppedWindowStart = current
            droppedAccum = 0L
            droppedElapsedMs = 0L
        }
        droppedAccum += droppedFrames
        droppedElapsedMs += elapsedMs
        _stats.update {
            it.copy(droppedFrames = it.droppedFrames + droppedFrames)
        }
        if (droppedElapsedMs < 5_000L) return
        val expected = droppedElapsedMs / 1000f * currentFps
        if (expected <= 0f) return
        val ratio = droppedAccum / expected
        _stats.update { it.copy(droppedFrameRatio = ratio) }
        if (ratio <= PlayerTuning.DROPPED_FRAME_RATIO) return
        if (current - lastDowngradeAt < PlayerTuning.DROPPED_FRAME_COOLDOWN_MS) return
        lastDowngradeAt = current
        droppedWindowStart = 0L
        val currentHeight = if (qualityHeight == 0) _info.value.height else qualityHeight
        val target = when {
            currentHeight > 1080 -> 1080
            currentHeight > 720 -> 720
            currentHeight > 480 -> 480
            else -> return
        }
        setQuality(target)
        _stats.update { it.copy(qualityDowngrades = it.qualityDowngrades + 1) }
        emit(PlayerEvent.AutoDowngrade(target, ratio))
        emit(PlayerEvent.Message("网络/性能不足，已切 ${target}p"))
        TvLog.w(TAG, "dropped frames ratio=${"%.2f".format(ratio)} -> ${target}p")
    }

    private fun handlePlayerError(error: PlaybackException) {
        val code = error.errorCodeName
        val message = error.message ?: "播放失败"
        TvLog.e(TAG, "player error code=$code msg=$message", error)
        _state.value = PlayerController.State.Error(code, message)
        val decoderIssue = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

        val media = lastMedia
        if (decoderIssue && !decoderRebuilt && media != null) {
            // 解码器初始化失败：重建一次播放器（旧的 codec 状态已经不可信）
            decoderRebuilt = true
            _stats.update { it.copy(decoderRebuilds = it.decoderRebuilds + 1) }
            emit(PlayerEvent.Message("解码器异常，正在重建播放器"))
            rebuildAndRetry(media, _position.value)
            return
        }
        emit(PlayerEvent.NeedsFallback(code, fallbackReason(code), media?.url))
    }

    private fun rebuildAndRetry(media: MediaUrl, positionMs: Long) {
        onMain {
            player?.let {
                it.removeListener(playerListener)
                it.removeAnalyticsListener(analyticsListener)
                runCatching { it.release() }
            }
            player = null
            audioSink = null
            trackSelector = null
            loadControl = null
            dataSourceFactory = null
            if (released) return@onMain
            play(media, positionMs)
        }
    }

    private fun resetSelfHealWindows() {
        underrunTimes.clear()
        lastEscalationAt = 0L
        droppedAccum = 0L
        droppedElapsedMs = 0L
        droppedWindowStart = 0L
        lastDowngradeAt = 0L
        lastResyncAt = 0L
        videoFormat = null
        currentFps = 30f
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun emit(event: PlayerEvent) {
        _events.tryEmit(event)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "Player"
        const val REBUFFER_WINDOW_MS = 10 * 60 * 1000L
        const val REBUFFER_BURST_THRESHOLD = 3
    }
}
