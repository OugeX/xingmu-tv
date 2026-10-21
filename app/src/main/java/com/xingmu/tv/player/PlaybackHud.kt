package com.xingmu.tv.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 长按连续 seek：按下立即 ±10s，按住 400ms 后每 200ms 再累加 10s，松手停止。
 * 点播走 seekBy（无边界）；直播走 seekBack/seekForward（DVR 窗口判定，超窗提示）。
 */
class SeekKeyRepeater(
    private val controller: PlayerControllerImpl,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit = {},
) {
    private var job: Job? = null

    fun onKeyDown(direction: Int): Boolean {
        direction.validate()
        if (job?.isActive == true) return true
        if (!step(direction)) return true
        job = scope.launch {
            delay(PlayerTuning.SEEK_LONG_PRESS_MS)
            while (isActive) {
                if (!step(direction)) break
                delay(PlayerTuning.SEEK_REPEAT_MS)
            }
        }
        return true
    }

    /** 单击 ±10s（HUD 按钮用，不进入长按循环）。 */
    fun seekOnce(direction: Int) {
        direction.validate()
        step(direction)
    }

    fun onKeyUp() {
        job?.cancel()
        job = null
    }

    private fun step(direction: Int): Boolean = if (controller.isLiveMode()) {
        val ok = if (direction < 0) controller.seekBack() else controller.seekForward()
        if (!ok) onMessage("该频道不支持回看")
        ok
    } else {
        controller.seekBy(direction * PlayerTuning.SEEK_STEP_MS)
        true
    }

    private fun Int.validate() {
        require(this == -1 || this == 1) { "direction 必须是 -1 或 +1" }
    }
}

/**
 * 播放 HUD（t4 可直接使用，也可只取 controller 的 StateFlow 自绘）。
 *
 * 覆盖任务 §4/§5 的可见能力：
 * - 音画校准 ±500ms（50ms 步进），落在 [PlayerControllerImpl.setAudioDelayMs]；
 * - 倍速 5 档循环；直播时点击给出的提示是“置灰”语义（不改速）；
 * - 单击/长按左右方向键 ±10s 连续 seek（[SeekKeyRepeater]）；
 * - 画面比例三档循环；
 * - 实时分辨率/码率/编码/解码器与 DVR 窗口。
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun PlaybackHud(
    controller: PlayerControllerImpl,
    visible: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    onExit: () -> Unit = {},
) {
    if (!visible) return

    val info by controller.playbackInfo.collectAsState()
    val position by controller.positionMs.collectAsState()
    val duration by controller.durationMs.collectAsState()
    val stats by controller.stats.collectAsState()
    val playerState by controller.state.collectAsState()
    val isPlaying = playerState is PlayerController.State.Playing
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val repeater = remember(controller) { SeekKeyRepeater(controller, scope) }
    var note by remember { mutableStateOf<String?>(null) }

    val accent = Color(0xFFFFC107)
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .onPreviewKeyEvent { event ->
                when (event.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (event.type == KeyEventType.KeyDown) repeater.onKeyDown(-1) else repeater.onKeyUp()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.type == KeyEventType.KeyDown) repeater.onKeyDown(1) else repeater.onKeyUp()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                        if (event.type == KeyEventType.KeyDown) repeater.onKeyDown(-1) else repeater.onKeyUp()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        if (event.type == KeyEventType.KeyDown) repeater.onKeyDown(1) else repeater.onKeyUp()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = title ?: "播放中",
                    style = TextStyle(color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold),
                )
                subtitle?.let {
                    Spacer(Modifier.width(10.dp))
                    HudTag(it, Color.White)
                }
                Spacer(Modifier.width(16.dp))
                HudTag(if (info.isLive) "LIVE" else "点播", accent)
                Spacer(Modifier.width(8.dp))
                HudTag("${info.resolutionText} · ${info.bitrateText} · ${info.codecText} · ${info.decoderName ?: "硬解/未知"}", Color.White)
                Spacer(Modifier.width(8.dp))
                HudTag("缓冲 ${stats.bufferEscalations} · 欠载 ${stats.audioUnderruns} · 掉帧 ${"%.0f".format(stats.droppedFrameRatio * 100)}%", Color.White)
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                note?.let {
                    BasicText(
                        text = it,
                        style = TextStyle(color = accent, fontSize = 18.sp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HudChip(if (isPlaying) "⏸ 暂停" else "▶ 播放") {
                        if (isPlaying) controller.pause() else controller.resume()
                    }
                    Spacer(Modifier.width(8.dp))
                    HudChip("−10s") { repeater.seekOnce(-1) }
                    Spacer(Modifier.width(8.dp))
                    HudChip("+10s") { repeater.seekOnce(1) }
                    Spacer(Modifier.width(16.dp))
                    if (info.isLive) {
                        // 直播模式隐藏进度条，只显示 LIVE + DVR 窗口
                        HudTag("LIVE", Color(0xFFD32F2F))
                        Spacer(Modifier.width(8.dp))
                        BasicText(
                            text = if (info.canSeekBack) "可回退 ${duration / 1000}s" else "不可回看",
                            style = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 18.sp),
                        )
                        Spacer(Modifier.weight(1f))
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White.copy(alpha = 0.25f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(6.dp)
                                    .background(accent),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        BasicText(
                            text = "${fmt(position)} / ${fmt(duration)}",
                            style = TextStyle(color = Color.White, fontSize = 18.sp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HudChip(
                        text = if (info.isLive) "倍速 ${info.speedText}（直播置灰）" else "倍速 ${info.speedText}",
                        dimmed = info.isLive,
                    ) {
                        if (info.isLive) {
                            note = "直播不支持倍速（会造成音画漂移）"
                        } else {
                            val speeds = PlayerTuning.SPEEDS
                            val next = speeds[(speeds.indexOfFirst { it == info.speed }.coerceAtLeast(0) + 1) % speeds.size]
                            controller.setSpeed(next)
                            note = "倍速 ${String.format("%.2fx", next)}（音调不变）"
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    HudChip("画质 ${QualityOptions.label(info.qualityHeight)}") {
                        val options = QualityOptions.ALL
                        val index = options.indexOfFirst { it.first == info.qualityHeight }.coerceAtLeast(0)
                        val next = options[(index + 1) % options.size].first
                        controller.setQuality(next)
                        note = "画质 -> ${QualityOptions.label(next)}（仅改 trackSelector，不重建播放器）"
                    }
                    Spacer(Modifier.width(8.dp))
                    HudChip("画面 ${info.aspect.label}") {
                        controller.setAspectMode(info.aspect.next())
                        note = "画面比例 -> ${info.aspect.next().label}"
                    }
                    Spacer(Modifier.width(8.dp))
                    HudChip("校准 ${offsetText(info.videoOffsetMs)}") {
                        controller.setAudioDelayMs(0)
                        note = "音画校准已复位"
                    }
                    Spacer(Modifier.width(4.dp))
                    HudChip("−50ms") {
                        controller.setAudioDelayMs(info.videoOffsetMs - PlayerTuning.CALIBRATION_STEP_MS)
                        note = "视频偏移 ${offsetText(info.videoOffsetMs - PlayerTuning.CALIBRATION_STEP_MS)}"
                    }
                    Spacer(Modifier.width(4.dp))
                    HudChip("+50ms") {
                        controller.setAudioDelayMs(info.videoOffsetMs + PlayerTuning.CALIBRATION_STEP_MS)
                        note = "视频偏移 ${offsetText(info.videoOffsetMs + PlayerTuning.CALIBRATION_STEP_MS)}"
                    }
                    Spacer(Modifier.width(16.dp))
                    HudChip("退出") { onExit() }
                }
            }
        }
    }
}

/** 缓冲态覆盖层：居中文案（低端机不做转圈动画，避免额外重绘）。 */
@Composable
fun PlaybackBufferingIndicator(
    controller: PlayerControllerImpl,
    modifier: Modifier = Modifier,
    text: String = "正在缓冲…",
) {
    val state by controller.state.collectAsState()
    if (state !is PlayerController.State.Buffering) return
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(
            text = text,
            style = TextStyle(color = Color.White, fontSize = 22.sp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

private fun fmt(ms: Long): String {    if (ms <= 0) return "00:00"
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun offsetText(ms: Int): String = if (ms == 0) "0ms" else "${if (ms > 0) "+" else ""}${ms}ms"

@Composable
private fun HudTag(text: String, color: Color) {
    BasicText(
        text = text,
        style = TextStyle(color = color, fontSize = 16.sp),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun HudChip(text: String, dimmed: Boolean = false, onClick: () -> Unit) {
    BasicText(
        text = text,
        style = TextStyle(
            color = if (dimmed) Color.White.copy(alpha = 0.45f) else Color.White,
            fontSize = 18.sp,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
