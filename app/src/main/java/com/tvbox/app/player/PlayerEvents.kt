package com.tvbox.app.player

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放层对外暴露的“事件 + 实时参数”。
 *
 * [PlayerController] 是冻结契约（t4 只依赖它），本文件是 t9 额外提供的增强接口：
 * t4 / LiveController 通过 `player as? PlayerEventSource` 之类的方式按需取用，
 * 不取也不影响基础播放。新增接口说明见 docs/PLAYER_IMPL.md 与任务汇报。
 */

/** 播放器主动上报的事件（自愈、换源、提示）。 */
sealed interface PlayerEvent {

    /** 播放错误 / 8s 无数据：调用方应换源或换台（保留最后画面，不黑屏）。 */
    data class NeedsFallback(
        val code: String,
        val reason: String,
        val url: String? = null,
    ) : PlayerEvent

    /** 起播 8s 或播放中 8s 无进度。 */
    data class NoData(val stalledMs: Long, val url: String? = null) : PlayerEvent

    /** 30s 窗口掉帧 >20%，已自动降档。 */
    data class AutoDowngrade(val toHeight: Int, val droppedRatio: Float) : PlayerEvent

    /** 30s 内音频欠载 ≥3 次，已提升缓冲（第二次触发时同时降档）。 */
    data class BufferEscalated(
        val level: Int,
        val live: Boolean,
        val qualityDowngraded: Boolean = false,
    ) : PlayerEvent

    /** 10 分钟窗口 rebuffer 次数超阈值，LiveController 据此降 safe_tier。 */
    data class RebufferBurst(val count: Int, val windowMs: Long) : PlayerEvent

    /** 视频格式突变后做了一次 seek 重同步。 */
    data class Resynced(val positionMs: Long) : PlayerEvent

    /** VOD 首片 4s 未到：调用方应切下一线路（或 PlayerController 自动切已注册 fallback）。 */
    data class VodSlowStart(val url: String?) : PlayerEvent

    /** 首帧已渲染（起播计时、切台延迟统计用）。 */
    data object FirstFrame : PlayerEvent

    /** prepare 完成耗时（PLAYER_PERF 埋点 t_prepare_ms）。 */
    data class Prepared(val prepareMs: Long) : PlayerEvent

    /** 面向用户的一次性提示（toast / 浮层文案）。 */
    data class Message(val text: String) : PlayerEvent
}

/** 实时播放参数（HUD / 频道信息面板读取）。 */
@Immutable
data class PlaybackInfo(
    val isLive: Boolean = false,
    /** HLS 直播窗口保留时长；0 表示不可回看 */
    val dvrWindowMs: Long = 0L,
    val canSeekBack: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val videoBitrate: Int = 0,
    val frameRate: Float = 0f,
    val videoMime: String? = null,
    val audioMime: String? = null,
    val audioChannels: Int = 0,
    val decoderName: String? = null,
    /** 当前画质档位上限（0=自动） */
    val qualityHeight: Int = 0,
    val speed: Float = 1f,
    /** 音画校准：视频相对音频的偏移（ms） */
    val videoOffsetMs: Int = 0,
    val aspect: AspectMode = AspectMode.FIT,
    val bandwidthBps: Long = 0L,
) {
    val resolutionText: String
        get() = if (width > 0 && height > 0) "${width}x${height}" else "—"

    val bitrateText: String
        get() = if (videoBitrate > 0) "${videoBitrate / 1000} kbps" else "—"

    val frameRateText: String
        get() = if (frameRate > 0f) String.format("%.0f fps", frameRate) else "—"

    val codecText: String
        get() = when {
            videoMime == null -> "—"
            videoMime.contains("avc", true) -> "H.264"
            videoMime.contains("hevc", true) -> "HEVC"
            else -> videoMime.substringAfterLast('/')
        }

    val speedText: String get() = if (speed == 1f) "1.0x" else String.format("%.2fx", speed)
}

/** 自愈计数器（debug HUD / 日志用，低端机排查时收益最大）。 */
@Immutable
data class PlaybackStats(
    val firstFrameMs: Long = 0L,
    val droppedFrames: Long = 0L,
    val droppedFrameRatio: Float = 0f,
    val audioUnderruns: Int = 0,
    val rebufferCount: Int = 0,
    val bufferEscalations: Int = 0,
    val qualityDowngrades: Int = 0,
    val decoderRebuilds: Int = 0,
)

/** 只读实时参数源。 */
interface PlaybackInfoSource {
    val playbackInfo: StateFlow<PlaybackInfo>
}

/** 事件源。 */
interface PlayerEventSource {
    val events: SharedFlow<PlayerEvent>
}

/** 播放统计源（PLAYER_EVT rebuffer 埋点用）。 */
interface PlaybackStatsSource {
    val stats: StateFlow<PlaybackStats>
}

/** 点播/直播参数切换（LiveController 在 play 前调用）。 */
interface PlayerProfileControl {
    fun setLiveMode(live: Boolean)
    fun isLiveMode(): Boolean
}

/** 直播 DVR 专用操作（仅 PlayerControllerImpl 实现）。 */
interface PlayerLiveOps {
    fun dvrWindowMs(): Long
    fun isDvrSeekable(): Boolean
    fun seekToLiveEdge()
    fun seekBack(seconds: Int = 10): Boolean
    fun seekForward(seconds: Int = 10): Boolean
}

/** 校准滑块范围校验，供 HUD 与控制器共用。 */
fun clampVideoOffset(ms: Int): Int =
    ms.coerceIn(-PlayerTuning.CALIBRATION_LIMIT_MS, PlayerTuning.CALIBRATION_LIMIT_MS)

/** 把错误码转成面向用户的中文（只用于提示，不影响错误码日志）。 */
internal fun fallbackReason(code: String): String = when {
    code.contains("DECODER", true) -> "当前设备解码失败，正在尝试其他线路"
    code.contains("UNSUPPORTED", true) -> "当前设备不支持该编码，正在换线路"
    code.contains("IO", true) || code.contains("NETWORK", true) -> "网络异常，正在切换线路"
    code.contains("PARSING", true) -> "播放列表解析失败，正在换线路"
    code.contains("NO_DATA", true) -> "连接超时（8 秒无数据），正在换线路"
    else -> "播放异常，正在切换线路"
}
