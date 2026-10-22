package com.xingmu.tv.ui.live

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xingmu.tv.live.LiveController
import com.xingmu.tv.live.LiveOverlay
import com.xingmu.tv.player.PlayerBootstrap
import com.xingmu.tv.player.VideoSurface
import com.xingmu.tv.search.PinyinIndex
import com.xingmu.tv.source.SourceRepository
import com.xingmu.tv.ui.LocalTvColors
import com.xingmu.tv.ui.LocalTvDimens
import com.xingmu.tv.ui.settings.SettingsStore
import com.xingmu.tv.ui.components.TvLabel
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
    // t30：收藏 / 最近（SharedPreferences + StateFlow）
    val favorites by live.channelPrefs.favorites.collectAsState()
    val recents by live.channelPrefs.recents.collectAsState()

    val rootFocus = remember { FocusRequester() }
    var overlayWasOpen by remember { mutableStateOf(false) }
    var overlayVisible by remember { mutableStateOf(false) }
    // t30：搜台过滤态下，根节点的数字键要交给输入框
    var panelFilterMode by remember { mutableStateOf(false) }

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
            com.xingmu.tv.live.BandwidthGate.zapping = true
            com.xingmu.tv.ui.ContentGate.update(true)
        } else {
            kotlinx.coroutines.delay(500)
            com.xingmu.tv.live.BandwidthGate.zapping = false
            com.xingmu.tv.ui.ContentGate.update(false)
        }
    }

    val overlayOpen = overlayVisible
    LaunchedEffect(overlayOpen) {
        if (overlayOpen) {
            overlayWasOpen = true
            // 分类面板自己把焦点放在选中的分组 Tab 上（不聚焦频道格 → 打开面板不会切台）
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
                    // 面板打开时 BACK/MENU/LEFT 交给面板自己处理（先退过滤态，再收起面板）
                    KeyEvent.KEYCODE_BACK -> when {
                        overlayVisible -> false
                        state.numberEntry.isNotEmpty() -> {
                            live.clearNumberEntry()
                            true
                        }
                        else -> false
                    }
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (overlayVisible) {
                            false
                        } else {
                            // t30：MENU 保留「显示全部」语义（安全模式下切一次再开面板）；LEFT 只开面板
                            if (code == KeyEvent.KEYCODE_MENU && state.safeMode) {
                                live.setShowAllChannels(!state.showAllChannels)
                            }
                            overlayVisible = true
                            true
                        }
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
                    // 面板关闭时，数字输入后按 OK 直达该台（t30：数字键 = 过滤 + OK 确认）
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (!overlayOpen && state.numberEntry.isNotEmpty()) {
                            live.confirmNumber()
                            true
                        } else {
                            false
                        }
                    }
                    else -> {
                        // 数字键直达（3s 内确认）；搜台过滤态交给输入框
                        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && !(overlayOpen && panelFilterMode)) {
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
            LiveCategoryPanel(
                state = state,
                favorites = favorites,
                recents = recents,
                pinyin = pinyin,
                onPreview = { channel -> live.preview(channel) },
                onConfirm = { channel ->
                    overlayVisible = false
                    live.confirmChannel(channel)
                },
                onToggleFavorite = { channel -> live.toggleFavorite(channel) },
                onDismiss = {
                    overlayVisible = false
                    live.dismissOverlay()
                },
                onFilterModeChange = { panelFilterMode = it },
                onToggleShowAll = { live.setShowAllChannels(!state.showAllChannels) },
            )
        }

        // t30：安全模式下「显示全部」时选中仅 1080p 的台，播放前二次确认（逻辑原本就有，补上渲染）
        com.xingmu.tv.live.UnsafeChannelConfirmDialog(
            channel = state.pendingUnsafe,
            onConfirm = { live.confirmUnsafePlay() },
            onCancel = { live.cancelUnsafePlay() },
        )
    }
}
