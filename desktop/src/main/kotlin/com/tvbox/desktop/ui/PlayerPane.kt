package com.tvbox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Canvas
import java.awt.Color as AwtColor

@Composable
fun PlayerPane(
    vm: AppViewModel,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onExit: () -> Unit,
) {
    val state = vm.player.state
    DisposableEffect(Unit) {
        onDispose { vm.player.detachCanvas() }
    }
    Column(Modifier.fillMaxSize().background(AppColors.videoBg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (fullscreen) AppColors.videoBg else AppColors.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton(if (fullscreen) "退出全屏" else "← 返回", onExit)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title, style = AppText.bodyStrong.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.subtitle, style = AppText.caption, maxLines = 1)
            }
            if (state.usedFallback) {
                Tag("已自动降级", AppColors.warning)
                Spacer(Modifier.width(8.dp))
            }
            Tag(if (state.isLive) "直播" else "点播", AppColors.accent)
            Spacer(Modifier.width(8.dp))
            Tag(state.attemptLabel.ifBlank { "准备中" })
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(AppColors.videoBg)) {
            if (vm.vlcRuntime != null && state.error == null) {
                SwingPanel(
                    modifier = Modifier.fillMaxSize(),
                    background = AppColors.videoBg,
                    factory = {
                        Canvas().apply {
                            background = AwtColor.BLACK
                            isFocusable = false
                        }
                    },
                    update = { canvas -> vm.player.attachCanvas(canvas) },
                )
            }
            val playerError = state.error
            if (playerError != null) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("播放失败", style = AppText.title.copy(color = AppColors.danger))
                    Spacer(Modifier.height(8.dp))
                    Text(playerError, style = AppText.body.copy(fontSize = 12.sp))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.playback?.let { request ->
                            PrimaryButton("重试", { vm.player.play(request, vm.settings) })
                        }
                        GhostButton("返回", onExit)
                    }
                }
            }
        }

        ControlBar(vm, fullscreen, onToggleFullscreen)
    }
}

@Composable
private fun ControlBar(vm: AppViewModel, fullscreen: Boolean, onToggleFullscreen: () -> Unit) {
    val state = vm.player.state
    Column(
        Modifier
            .fillMaxWidth()
            .background(AppColors.surface)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(AppColors.accent, CircleShape)
                    .clickable { vm.player.togglePause() },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (state.paused) "▶" else "❚❚", style = AppText.bodyStrong.copy(color = AppColors.accentOn, fontSize = 13.sp))
            }
            Spacer(Modifier.width(12.dp))
            Text(formatTime(state.timeMs), style = AppText.mono.copy(color = AppColors.textSecondary), modifier = Modifier.width(56.dp))

            if (!state.isLive && state.lengthMs > 0) {
                Slider(
                    value = (state.timeMs.toFloat() / state.lengthMs).coerceIn(0f, 1f),
                    onValueChange = { vm.player.seekTo((it * state.lengthMs).toLong()) },
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = AppColors.accent,
                        activeTrackColor = AppColors.accent,
                        inactiveTrackColor = AppColors.divider,
                    ),
                )
                Text(formatTime(state.lengthMs), style = AppText.mono.copy(color = AppColors.textMuted), modifier = Modifier.width(56.dp))
            } else {
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.isLive) "直播 · 支持 ±10s 时移（视源而定）" else "正在读取时长…",
                    style = AppText.caption.copy(fontSize = 10.sp),
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }

            Spacer(Modifier.width(10.dp))
            Text("${state.volume}%", style = AppText.caption, modifier = Modifier.width(36.dp))
            Slider(
                value = state.volume / 100f,
                onValueChange = { vm.player.setVolume((it * 100).toInt()) },
                modifier = Modifier.width(84.dp),
                colors = SliderDefaults.colors(
                    thumbColor = AppColors.accent,
                    activeTrackColor = AppColors.accent,
                    inactiveTrackColor = AppColors.divider,
                ),
            )
            Spacer(Modifier.width(8.dp))
            GhostButton("${formatRate(state.rate)}×", { vm.player.setRate(nextRate(state.rate)) })
            Spacer(Modifier.width(8.dp))
            GhostButton(if (fullscreen) "退出全屏" else "全屏", onToggleFullscreen)
        }

        Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    state.error != null -> "播放失败"
                    state.buffering -> "缓冲中…（${state.attemptLabel}）"
                    state.playing -> "正在播放 · ${state.attemptLabel}"
                    else -> state.statusText
                },
                style = AppText.caption.copy(fontSize = 10.sp),
            )
            Spacer(Modifier.weight(1f))
            Text("空格 暂停 · ←→ 快退快进 · ↑↓ 音量 · F 全屏 · Esc 退出", style = AppText.caption.copy(fontSize = 10.sp))
        }
        state.notice?.let {
            Spacer(Modifier.height(3.dp))
            Text(it, style = AppText.caption.copy(fontSize = 10.sp, color = AppColors.warning), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val RATES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun nextRate(current: Float): Float {
    val index = RATES.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return RATES[(index + 1).mod(RATES.size)]
}

private fun formatRate(rate: Float): String =
    if (rate == rate.toInt().toFloat()) "${rate.toInt()}" else rate.toString()

fun formatTime(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
