package com.tvbox.app.ui.casual

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.app.data.casual.RadioSession
import com.tvbox.app.data.casual.RadioStation
import com.tvbox.app.ui.LocalTvColors
import com.tvbox.app.ui.LocalTvDimens
import com.tvbox.app.ui.LocalTvMode
import com.tvbox.app.ui.LocalTvTier
import com.tvbox.app.ui.TvMode
import com.tvbox.app.ui.components.TvButton
import com.tvbox.app.ui.components.TvFocusable
import com.tvbox.app.ui.components.TvLabel
import kotlinx.coroutines.delay

/**
 * 听页（docs/CASUAL_SPEC.md §2.1）：全屏暗场、大号台名、码率、简易电平条。
 * 进入即播上次电台；CH+/CH- 或 ↑↓ 切台；OK 暂停；←→ 调系统音量；MENU 电台列表。
 * C 保命模式关闭电平动效。
 */
@Composable
fun CasualRadioScreen(session: RadioSession, modifier: Modifier = Modifier, onOpenClips: () -> Unit = {}) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val mode = LocalTvMode.current
    val context = LocalContext.current
    val state by session.state.collectAsState()

    val rootFocus = remember { FocusRequester() }
    val firstStationFocus = remember { FocusRequester() }
    var listVisible by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    LaunchedEffect(Unit) {
        session.start()
        delay(80)
        runCatching { rootFocus.requestFocus() }
    }

    LaunchedEffect(listVisible) {
        if (listVisible) {
            delay(80)
            runCatching { firstStationFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    LaunchedEffect(state.playing, mode) {
        if (!state.playing || mode == TvMode.LITE) return@LaunchedEffect
        while (true) {
            tick = (tick + 1) % 1000
            delay(120)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (mode == TvMode.LITE) {
                    Brush.verticalGradient(listOf(Color.Black, Color.Black))
                } else {
                    Brush.verticalGradient(
                        listOf(Color(0xFF121014), Color(0xFF08080A), Color.Black),
                    )
                },
            )
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        if (listVisible) {
                            listVisible = false
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        listVisible = !listVisible
                        true
                    }
                    KeyEvent.KEYCODE_CHANNEL_UP -> {
                        session.next(); true
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        session.previous(); true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (listVisible) false else { session.next(); true }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (listVisible) false else { session.previous(); true }
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (listVisible) {
                            false
                        } else {
                            session.togglePause(); true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI,
                        )
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE,
                            AudioManager.FLAG_SHOW_UI,
                        )
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TvLabel("随便听", dimens.cardTitle, colors.accent, weight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                TvLabel(state.source.ifBlank { "电台" }, dimens.caption, colors.textMuted)
                Spacer(Modifier.width(1.dp).weight(1f))
                TvButton("电台列表", onClick = { listVisible = true })
            }

            Spacer(Modifier.weight(1f))

            TvLabel(
                text = state.station?.name ?: if (state.loading) "正在准备电台…" else "暂无电台",
                fontSize = if (LocalTvTier.current.isLarge) dimens.heroTitle * 1.05f else dimens.heroTitle * 0.75f,
                color = colors.text,
                weight = FontWeight.Black,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            TvLabel(
                text = listOfNotNull(
                    state.station?.group?.takeIf { it.isNotBlank() },
                    state.station?.tag?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                fontSize = dimens.body,
                color = colors.textSecondary,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            TvLabel(
                text = buildString {
                    append(state.station?.codec ?: "UNKNOWN")
                    val bitrate = state.station?.bitrateKbps ?: 0
                    if (bitrate > 0) append(" · ${bitrate} kbps") else append(" · 码率未知")
                    append(" · 第 ${(state.index + 1).coerceAtLeast(1)}/${state.total} 台")
                },
                fontSize = dimens.caption,
                color = colors.textMuted,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(22.dp))
            LevelBars(
                playing = state.playing,
                animate = mode == TvMode.CINEMA,
                tick = tick,
                accent = colors.accent,
                muted = colors.textMuted,
            )
            Spacer(Modifier.height(10.dp))
            TvLabel(
                text = when {
                    state.loading -> "加载中…"
                    state.buffering -> "缓冲中…"
                    state.playing -> "正在播放"
                    else -> "已暂停"
                },
                fontSize = dimens.body,
                color = if (state.playing) colors.accent else colors.textSecondary,
                weight = FontWeight.SemiBold,
            )
            state.message?.let { message ->
                Spacer(Modifier.height(4.dp))
                TvLabel(message, dimens.caption, colors.textMuted, maxLines = 1)
            }

            Spacer(Modifier.weight(1f))
            TvLabel(
                "CH+/CH- 或 ↑↓ 切台 · OK 暂停 · ←→ 系统音量 · MENU 电台列表",
                fontSize = dimens.caption,
                color = colors.textMuted,
                maxLines = 1,
            )
        }

        if (listVisible) {
            StationListOverlay(
                stations = session.currentStations(),
                currentId = state.station?.id,
                firstFocus = firstStationFocus,
                onPick = { station ->
                    val index = session.currentStations().indexOfFirst { it.id == station.id }
                    if (index >= 0) session.playIndex(index)
                    listVisible = false
                },
                onClose = { listVisible = false },
                onOpenClips = {
                    listVisible = false
                    onOpenClips()
                },
            )
        }
    }
}

@Composable
private fun LevelBars(
    playing: Boolean,
    animate: Boolean,
    tick: Int,
    accent: Color,
    muted: Color,
) {
    Row(
        modifier = Modifier.height(54.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bars = 28
        repeat(bars) { index ->
            val height = if (playing && animate) {
                10 + ((tick * 7 + index * 17) % 40)
            } else if (playing) {
                18 + ((index * 11) % 22)
            } else {
                8 + (index % 5) * 2
            }
            Box(
                Modifier
                    .width(6.dp)
                    .height(height.dp)
                    .background(if (playing) accent else muted.copy(alpha = 0.5f)),
            )
        }
    }
}

@Composable
private fun StationListOverlay(
    stations: List<RadioStation>,
    currentId: String?,
    firstFocus: FocusRequester,
    onPick: (RadioStation) -> Unit,
    onClose: () -> Unit,
    onOpenClips: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f))) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.92f))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvLabel("电台列表", dimens.cardTitle, colors.text, weight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                TvLabel("${stations.size} 个", dimens.caption, colors.textMuted)
                Spacer(Modifier.width(1.dp).weight(1f))
                TvButton("看视频 ›", onClick = onOpenClips)
                Spacer(Modifier.width(8.dp))
                TvButton("关闭", onClick = onClose)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(stations, key = { it.id }) { station ->
                    val selected = station.id == currentId
                    val isFirst = station.id == stations.firstOrNull()?.id
                    TvFocusable(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isFirst) Modifier.focusRequester(firstFocus) else Modifier),
                        onClick = { onPick(station) },
                    ) { focused ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (selected) colors.surfaceHigh else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TvLabel(
                                text = station.name,
                                fontSize = dimens.caption,
                                color = if (selected || focused) colors.accent else colors.textSecondary,
                                weight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            TvLabel(
                                text = if (station.bitrateKbps > 0) "${station.bitrateKbps}k" else station.codec,
                                fontSize = dimens.caption,
                                color = colors.textMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            TvLabel("UP/DOWN 选择 · OK 播放 · BACK 关闭", dimens.caption, colors.textMuted, maxLines = 1)
        }
    }
}
