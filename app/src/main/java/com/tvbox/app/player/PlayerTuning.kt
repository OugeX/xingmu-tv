package com.tvbox.app.player

import android.annotation.SuppressLint

import androidx.media3.ui.AspectRatioFrameLayout

/**
 * 播放器硬编码调参（docs/PLAYBACK_SPEC.md §1/§4/§5、docs/AVSYNC_SPEC.md §1-§4）。
 *
 * 低端盒子（1GB / RK3229 / Amlogic S905 级别）的安全上限，任何一处放松都会直接表现为花屏、掉帧、
 * 音画漂移，因此全部集中在 [PlayerTuning]，不在业务代码里散落魔法数字。
 */
object PlayerTuning {

    // ---- 解码上限（PLAYBACK_SPEC §1 保险 1） ----
    const val MAX_VIDEO_WIDTH = 1920
    const val MAX_VIDEO_HEIGHT = 1080
    const val MAX_VIDEO_FRAME_RATE = 30
    const val MAX_VIDEO_BITRATE = 6_000_000

    // ---- AudioTrack 缓冲（AVSYNC_SPEC §3：≥400ms） ----
    const val AUDIO_BUFFER_MIN_US = 400_000
    const val AUDIO_BUFFER_MAX_US = 800_000
    const val AUDIO_BUFFER_MULTIPLIER = 4

    // ---- 自愈阈值（AVSYNC_SPEC §4） ----
    const val UNDERRUN_WINDOW_MS = 30_000L
    const val UNDERRUN_THRESHOLD = 3
    const val DROPPED_FRAME_WINDOW_MS = 30_000L
    const val DROPPED_FRAME_RATIO = 0.20f
    const val DROPPED_FRAME_COOLDOWN_MS = 60_000L
    const val RESYNC_COOLDOWN_MS = 5_000L
    const val NO_DATA_TIMEOUT_MS = 8_000L
    const val FIRST_FRAME_TIMEOUT_MS = 8_000L

    // ---- 交互（PLAYBACK_SPEC §4/§5） ----
    const val SEEK_STEP_MS = 10_000L          // 单击 ±10s
    const val SEEK_REPEAT_MS = 200L           // 长按每 200ms 累加 10s
    const val SEEK_LONG_PRESS_MS = 400L       // 按住 400ms 进入连续 seek
    const val CALIBRATION_LIMIT_MS = 500      // 音画校准 ±500ms
    const val CALIBRATION_STEP_MS = 50
    const val LIVE_DVR_MIN_WINDOW_MS = 5_000L // 小于 5s 视为不可回看

    /** 5 档倍速；直播全部置灰（PLAYBACK_SPEC §4）。 */
    val SPEEDS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    // ---- t25 / PERF_ROADMAP P2-9：解码器会话复用开关（默认关） ----
    /**
     * 关闭时 = 现状：每次切台 stop → setMediaItem → prepare，Media3 会释放并重建 MediaCodec
     * （真机 init 55–68ms）。
     *
     * 打开时：Renderers 关掉 decoder fallback 探测（少一次失败的 codec init），并把
     * `CODEC_EVT action=reuse` 的复用评估打全。**注意**：Media3 1.5.1 公共 API 没有“跨 MediaItem
     * 保留同一个 MediaCodec 会话”的入口（`MediaCodecRenderer.onDisabled()` 必 release），所以
     * 真正意义上的会话复用要等自研 MediaCodecAdapter 池或 Media3 升级；开关先留给真机 A/B。
     */
    @Volatile
    var codecReuseEnabled: Boolean = false

    fun toggleCodecReuse(enabled: Boolean) {
        codecReuseEnabled = enabled
    }
}

/** 点播/直播两套 DefaultLoadControl 参数（PLAYBACK_SPEC §1 保险 4）。 */
enum class BufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
    val backBufferMs: Int,
) {
    /** 点播：min 8s / max 25s / 12MB / 起播 1.2s / 重缓 3s（t22 起播优先，稳定后再回涨） */
    VOD(8_000, 25_000, 1_200, 3_000, 12 * 1024 * 1024, 10_000),

    /** 点播欠载后的“提缓冲一档”（AVSYNC_SPEC §4） */
    VOD_SAFE(15_000, 30_000, 2_000, 4_000, 16 * 1024 * 1024, 10_000),

    /** 直播：min 4s / max 15s / 8MB / bufferForPlayback 1.2s / afterRebuffer 3s（t20 降 rebuffer） */
    LIVE(4_000, 15_000, 1_200, 3_000, 8 * 1024 * 1024, 0),

    /** 直播欠载后的“提缓冲一档” */
    LIVE_SAFE(6_000, 20_000, 2_000, 4_000, 10 * 1024 * 1024, 0),
}

/** 画面比例三档（PLAYBACK_SPEC §6 / 任务 §5）。 */
@SuppressLint("UnsafeOptInUsageError")
enum class AspectMode(val label: String, val resizeMode: Int) {
    FIT("原始比例", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("拉伸铺满", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("裁剪铺满", AspectRatioFrameLayout.RESIZE_MODE_ZOOM);

    fun next(): AspectMode = entries[(ordinal + 1) % entries.size]
}

/** 画质档位：只改 trackSelector 参数，不重建播放器（PLAYBACK_SPEC §6）。 */
object QualityOptions {

    /** 0 = 自动（仍受 1080p30/6Mbps 上限约束）；2160 = 4K 优先（仅设备支持时由 LiveController 放开） */
    val ALL: List<Pair<Int, String>> = listOf(
        0 to "自动",
        2160 to "4K",
        1080 to "1080p",
        720 to "720p",
        480 to "480p",
    )

    fun label(maxHeight: Int): String = ALL.firstOrNull { it.first == maxHeight }?.second ?: "自动"

    fun maxSize(maxHeight: Int): Pair<Int, Int> = when (maxHeight) {
        480 -> 854 to 480
        720 -> 1280 to 720
        2160 -> 3840 to 2160
        else -> PlayerTuning.MAX_VIDEO_WIDTH to PlayerTuning.MAX_VIDEO_HEIGHT
    }

    fun maxBitrate(maxHeight: Int): Int = when (maxHeight) {
        480 -> 1_500_000
        720 -> 3_000_000
        2160 -> 20_000_000
        else -> PlayerTuning.MAX_VIDEO_BITRATE
    }
}
