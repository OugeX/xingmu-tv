package com.tvbox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.delay

@Composable
fun AppRoot(vm: AppViewModel, windowState: WindowState, onClose: () -> Unit) {
    AppTheme {
        var fullscreen by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }
        val playerActive = vm.player.state.active

        fun setFullscreen(value: Boolean) {
            fullscreen = value
            windowState.placement = if (value) WindowPlacement.Fullscreen else WindowPlacement.Floating
        }

        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        LaunchedEffect(playerActive) { focusRequester.requestFocus() }
        LaunchedEffect(vm.section) { focusRequester.requestFocus() }

        // Compose 的焦点可能在 AWT Canvas / 按钮上，键盘快捷键用 AWT 级 KeyEventDispatcher 兜底，
        // 保证空格、←→、F、Esc 在播放器里始终可用（输入框场景下 playerActive=false，不会截胡打字）。
        DisposableEffect(Unit) {
            val dispatcher = java.awt.KeyEventDispatcher { event ->
                if (event.id != java.awt.event.KeyEvent.KEY_PRESSED || !vm.player.state.active) {
                    false
                } else {
                    val activeWindow = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                    if ((activeWindow as? java.awt.Frame)?.title != "星幕 TV") {
                        false
                    } else {
                        when (event.keyCode) {
                            java.awt.event.KeyEvent.VK_SPACE -> {
                                vm.player.togglePause()
                                true
                            }
                            java.awt.event.KeyEvent.VK_LEFT -> {
                                vm.player.seekBy(-10_000)
                                true
                            }
                            java.awt.event.KeyEvent.VK_RIGHT -> {
                                vm.player.seekBy(10_000)
                                true
                            }
                            java.awt.event.KeyEvent.VK_UP -> {
                                vm.player.setVolume(vm.player.state.volume + 5)
                                true
                            }
                            java.awt.event.KeyEvent.VK_DOWN -> {
                                vm.player.setVolume(vm.player.state.volume - 5)
                                true
                            }
                            java.awt.event.KeyEvent.VK_F -> {
                                setFullscreen(!fullscreen)
                                true
                            }
                            java.awt.event.KeyEvent.VK_ESCAPE -> {
                                if (fullscreen) setFullscreen(false) else vm.stopPlayback()
                                true
                            }
                            else -> false
                        }
                    }
                }
            }
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
            onDispose {
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
            }
        }

        val keyHandler: (KeyEvent) -> Boolean = { event ->
            if (event.type != KeyEventType.KeyDown) {
                false
            } else if (!playerActive) {
                if (event.key == Key.Enter && vm.section == Section.SEARCH && vm.searchQuery.isNotBlank()) {
                    vm.search(vm.searchQuery)
                    true
                } else {
                    false
                }
            } else {
                when (event.key) {
                    Key.Spacebar -> {
                        vm.player.togglePause()
                        true
                    }
                    Key.DirectionLeft -> {
                        vm.player.seekBy(-10_000)
                        true
                    }
                    Key.DirectionRight -> {
                        vm.player.seekBy(10_000)
                        true
                    }
                    Key.DirectionUp -> {
                        vm.player.setVolume(vm.player.state.volume + 5)
                        true
                    }
                    Key.DirectionDown -> {
                        vm.player.setVolume(vm.player.state.volume - 5)
                        true
                    }
                    Key.F -> {
                        setFullscreen(!fullscreen)
                        true
                    }
                    Key.Escape -> {
                        if (fullscreen) {
                            setFullscreen(false)
                        } else {
                            vm.stopPlayback()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(AppColors.bg)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent(keyHandler),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    if (!fullscreen) {
                        LeftNav(vm, Modifier.width(238.dp).fillMaxHeight())
                        VerticalRule()
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (playerActive) {
                            PlayerPane(
                                vm = vm,
                                fullscreen = fullscreen,
                                onToggleFullscreen = { setFullscreen(!fullscreen) },
                                onExit = {
                                    vm.stopPlayback()
                                    if (fullscreen) setFullscreen(false)
                                },
                            )
                        } else {
                            when (vm.section) {
                                Section.LIVE -> LivePane(vm)
                                Section.MOVIE, Section.TV, Section.ANIME, Section.VARIETY -> BrowsePane(vm)
                                Section.SEARCH -> SearchPane(vm)
                                Section.SOURCES -> SourcesPane(vm)
                                Section.SETTINGS -> SettingsPane(vm)
                            }
                        }
                    }
                    if (!fullscreen) {
                        VerticalRule()
                        InfoPanel(vm, { setFullscreen(true) }, Modifier.width(392.dp).fillMaxHeight())
                    }
                }
                if (!fullscreen) StatusBar(vm)
            }
            ToastHost(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 46.dp))
        }
    }
}

@Composable
private fun VerticalRule() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(AppColors.divider))
}

@Composable
private fun LeftNav(vm: AppViewModel, modifier: Modifier = Modifier) {
    Column(modifier.background(AppColors.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("星幕", style = AppText.brand.copy(color = AppColors.accent))
            Spacer(Modifier.width(6.dp))
            Text("TV", style = AppText.brand)
        }
        Text("XINGMU TV · PC v0.1", style = AppText.caption.copy(fontSize = 9.sp, letterSpacing = 1.sp))
        Spacer(Modifier.height(22.dp))

        listOf(Section.LIVE, Section.MOVIE, Section.TV, Section.ANIME, Section.VARIETY).forEach { section ->
            NavItem(section, vm.section == section, vm.player.state.active) { vm.openSection(section) }
            Spacer(Modifier.height(2.dp))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.divider))
        Spacer(Modifier.height(10.dp))
        listOf(Section.SEARCH, Section.SOURCES, Section.SETTINGS).forEach { section ->
            NavItem(section, vm.section == section, vm.player.state.active) { vm.openSection(section) }
            Spacer(Modifier.height(2.dp))
        }

        Spacer(Modifier.weight(1f))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.surfaceHigh)
                .clickable { vm.openSection(Section.SETTINGS) }
                .padding(10.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(if (vm.vlcRuntime != null) AppColors.success else AppColors.danger, RoundedCornerShape(50)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (vm.vlcRuntime != null) "libVLC 就绪" else "libVLC 未配置",
                        style = AppText.caption.copy(color = AppColors.textSecondary, fontSize = 10.sp),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    vm.vlcMessage,
                    style = AppText.caption.copy(fontSize = 9.sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("源 ${vm.sources.size} · 频道 ${vm.live?.channels?.size ?: 0}", style = AppText.caption.copy(fontSize = 9.sp))
    }
}

@Composable
private fun NavItem(section: Section, selected: Boolean, playing: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) AppColors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(16.dp)
                .background(if (selected) AppColors.accent else Color.Transparent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                section.label,
                style = AppText.bodyStrong.copy(
                    fontSize = 13.sp,
                    color = if (selected) AppColors.accent else AppColors.textSecondary,
                ),
            )
            Text(section.kicker, style = AppText.caption.copy(fontSize = 8.sp, letterSpacing = 1.sp))
        }
        if (section == Section.LIVE && playing) {
            Tag("播放中", AppColors.accent)
        }
    }
}

@Composable
private fun StatusBar(vm: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(AppColors.surface)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(vm.statusLine, style = AppText.caption.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Text(
            "空格 暂停 · ←→ 快进 · F 全屏 · Esc 退出",
            style = AppText.caption.copy(fontSize = 9.sp),
        )
    }
}

@Composable
private fun ToastHost(vm: AppViewModel, modifier: Modifier = Modifier) {
    val toast = vm.toast
    if (toast != null) {
        LaunchedEffect(toast.id) {
            delay(4200)
            vm.dismissToast(toast.id)
        }
        Box(modifier) {
            Box(
                Modifier
                    .background(
                        if (toast.error) AppColors.danger.copy(alpha = 0.92f) else AppColors.surfaceHigh.copy(alpha = 0.97f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(
                    toast.message,
                    style = AppText.body.copy(
                        fontSize = 11.sp,
                        color = if (toast.error) Color.White else AppColors.textSecondary,
                    ),
                )
            }
        }
    }
}
