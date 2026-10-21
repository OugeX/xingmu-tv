package com.tvbox.app.ui.live

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvbox.app.live.LiveChannel
import com.tvbox.app.live.LiveController
import com.tvbox.app.live.LiveHealth
import com.tvbox.app.live.LiveOverlay
import com.tvbox.app.player.PlayerBootstrap
import com.tvbox.app.player.VideoSurface
import com.tvbox.app.search.PinyinIndex
import com.tvbox.app.source.SourceRepository
import com.tvbox.app.ui.LocalTvColors
import com.tvbox.app.ui.LocalTvDimens
import com.tvbox.app.ui.settings.SettingsStore
import com.tvbox.app.ui.components.TvFocusable
import com.tvbox.app.ui.components.TvLabel
import kotlinx.coroutines.delay

/**
 * 直播页（docs/UI_SPEC.md §3.6）：默认全屏播放上次频道；频道列表是 LEFT/MENU 呼出的浮层，
 * 视频继续播放不暂停；浮层内焦点移动走 800ms 防抖预览，OK 确认收起，CH+/CH- 与数字键直达。
 */
@Composable
fun LiveScreen(
    repo: SourceRepository,
    pinyin: PinyinIndex,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current

    val player = remember { PlayerBootstrap.createAndRegister(context) }
    val appSettings = remember { SettingsStore(context).read() }
    val live = remember(player) { LiveController(context.applicationContext, player) }
    val state by live.state.collectAsState()

    val rootFocus = remember { FocusRequester() }
    val firstChannelFocus = remember { FocusRequester() }
    var overlayWasOpen by remember { mutableStateOf(false) }
    var overlayVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(120)
        runCatching { rootFocus.requestFocus() }
    }

    LaunchedEffect(Unit) {
        live.setPrefer4K(appSettings.prefer4K)
        player.setQuality(appSettings.qualityCap)
        player.setAudioDelayMs(appSettings.audioDelayMs)
    }

    DisposableEffect(player) {
        live.start()
        onDispose {
            live.release()
            PlayerBootstrap.release(player)
        }
    }

    // 起播策略交给 LiveController（上次频道 → 高清专区首个 ≥1080p）；这里只同步带宽门控
    LaunchedEffect(state.switching) {
        if (state.switching) {
            com.tvbox.app.live.BandwidthGate.zapping = true
            com.tvbox.app.ui.ContentGate.update(true)
        } else {
            kotlinx.coroutines.delay(500)
            com.tvbox.app.live.BandwidthGate.zapping = false
            com.tvbox.app.ui.ContentGate.update(false)
        }
    }

    val overlayOpen = overlayVisible
    LaunchedEffect(overlayOpen) {
        if (overlayOpen) {
            overlayWasOpen = true
            delay(80)
            runCatching { firstChannelFocus.requestFocus() }
        } else if (overlayWasOpen) {
            runCatching { rootFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    // 仍把按键交给 LiveController 判断 CH+/CH- 的 KeyUp
                    return@onPreviewKeyEvent live.handleKey(
                        event.key.nativeKeyCode,
                        KeyEvent.ACTION_UP,
                        0,
                    )
                }
                val code = event.key.nativeKeyCode
                when (code) {
                    KeyEvent.KEYCODE_BACK -> {
                        if (overlayVisible) {
                            overlayVisible = false
                            live.dismissOverlay()
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT -> {
                        overlayVisible = !overlayVisible
                        if (!overlayVisible) live.dismissOverlay()
                        true
                    }
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> live.handleKey(
                        code,
                        KeyEvent.ACTION_DOWN,
                        0,
                    )
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!overlayOpen) {
                            live.onDpadSwitch(if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                            true
                        } else {
                            false
                        }
                    }
                    else -> {
                        // 数字键直达（1.5s 内确认）
                        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                            live.handleKey(code, KeyEvent.ACTION_DOWN, 0)
                        } else {
                            false
                        }
                    }
                }
            },
    ) {
        VideoSurface(controller = player, modifier = Modifier.fillMaxSize())

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvLabel(
                text = state.current?.name ?: "直播",
                fontSize = dimens.cardTitle,
                color = colors.text,
                weight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.width(12.dp))
            TvLabel(
                text = state.statusText,
                fontSize = dimens.caption,
                color = if (state.error != null) colors.danger else colors.textSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.width(1.dp).weight(1f))
            TvLabel("LEFT/MENU 频道 · CH+/CH- 切台 · 数字直达", dimens.caption, colors.textMuted, maxLines = 1)
        }

        state.current?.let { channel ->
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp),
            ) {
                TvLabel(
                    text = "${channel.groupName} · ${channel.quality ?: "清晰度未知"} · ${channel.lines.size} 线路",
                    fontSize = dimens.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
                TvLabel(
                    text = state.epg.text,
                    fontSize = dimens.caption,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }

        state.message?.let { message ->
            TvLabel(
                text = message,
                fontSize = dimens.caption,
                color = colors.accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp),
                maxLines = 2,
            )
        }

        if (overlayOpen) {
            ChannelOverlay(
                state = state,
                firstChannelFocus = firstChannelFocus,
                onPreview = { channel -> live.preview(channel) },
                onConfirm = { channel ->
                    overlayVisible = false
                    live.confirmChannel(channel)
                },
                onDismiss = {
                    overlayVisible = false
                    live.dismissOverlay()
                },
            )
        }
    }
}

@Composable
private fun ChannelOverlay(
    state: com.tvbox.app.live.LiveState,
    firstChannelFocus: FocusRequester,
    onPreview: (LiveChannel) -> Unit,
    onConfirm: (LiveChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.92f))
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvLabel("频道", dimens.cardTitle, colors.text, weight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                TvLabel("${state.channels.size} 个 · ${state.groups.size} 组", dimens.caption, colors.textMuted)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                state.groups.forEach { group ->
                    item(key = "header-${group.id}") {
                        TvLabel(
                            text = "${group.name}（${group.channelCount}）",
                            fontSize = dimens.caption,
                            color = colors.accent,
                            weight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                        )
                    }
                    items(state.groupedChannels[group.name].orEmpty(), key = { it.id }, contentType = { "channel" }) { channel ->
                        val isFirst = group.id == state.groups.firstOrNull()?.id &&
                            channel.id == state.groupedChannels[group.name]?.firstOrNull()?.id
                        ChannelRow(
                            channel = channel,
                            selected = channel.id == state.current?.id,
                            modifier = if (isFirst) Modifier.focusRequester(firstChannelFocus) else Modifier,
                            onFocused = { onPreview(channel) },
                            onClick = { onConfirm(channel) },
                        )
                    }
                }
            }
            TvLabel("LEFT/BACK 收起 · 焦点预览切台（800ms）· OK 确认", dimens.caption, colors.textMuted, maxLines = 1)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .width(440.dp)
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(18.dp),
        ) {
            val channel = state.current
            TvLabel(channel?.name ?: "未选择频道", dimens.rowTitle, colors.text, weight = FontWeight.Bold, maxLines = 1)
            TvLabel(
                channel?.let { "${it.groupName} · 台号 ${it.number} · ${it.health.label}" }.orEmpty(),
                dimens.caption,
                colors.textSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            TvLabel(state.statusText, dimens.caption, colors.textMuted, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            TvLabel(state.epg.text, dimens.caption, colors.textSecondary, maxLines = 3)
            channel?.desc?.let {
                Spacer(Modifier.height(6.dp))
                TvLabel(it, dimens.caption, colors.textMuted, maxLines = 3)
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: LiveChannel,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    TvFocusable(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocused() },
        onClick = onClick,
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) colors.surfaceHigh else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvLabel(
                text = "%03d".format(channel.number),
                fontSize = dimens.caption,
                color = if (selected || focused) colors.accent else colors.textMuted,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            TvLabel(
                text = channel.name,
                fontSize = dimens.caption,
                color = if (selected) colors.text else colors.textSecondary,
                weight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (channel.health == LiveHealth.DEGRADED) {
                TvLabel("线路不稳", dimens.caption, colors.danger, maxLines = 1)
            } else {
                TvLabel("${channel.lines.size}线", dimens.caption, colors.textMuted, maxLines = 1)
            }
        }
    }
}
