package com.tvbox.app.ui.casual

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tvbox.app.core.Result
import com.tvbox.app.data.casual.CasualRepository
import com.tvbox.app.data.casual.ShortClip
import com.tvbox.app.player.PlaybackHud
import com.tvbox.app.player.PlayerBootstrap
import com.tvbox.app.player.PlayerController
import com.tvbox.app.player.PlayerControllerImpl
import com.tvbox.app.player.VideoSurface
import com.tvbox.app.ui.LocalTvDimens
import com.tvbox.app.ui.settings.SettingsStore
import com.tvbox.app.ui.components.TvButton
import com.tvbox.app.ui.components.TvLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 看页全屏播放（短视频）：
 * - 进入即 resolve → play，playurl 失败由仓库降 qn 重试一次；
 * - 播完自动下一条（默认开，可关）；
 * - HUD 复用点播（倍速/进度/音画校准），但不提供切源/选集。
 */
@Composable
fun CasualVideoScreen(
    clips: List<ShortClip>,
    startIndex: Int,
    repo: CasualRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()
    val controller: PlayerControllerImpl = remember { PlayerBootstrap.createAndRegister(context) }
    val appSettings = remember { SettingsStore(context).read() }
    val state by controller.state.collectAsState()

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (clips.size - 1).coerceAtLeast(0))) }
    var autoNext by remember { mutableStateOf(true) }
    var hudVisible by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var switcher by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val current = clips.getOrNull(index)

    fun advance() {
        if (clips.isEmpty()) return
        index = (index + 1) % clips.size
    }

    fun playIndex(target: Int) {
        val clip = clips.getOrNull(target) ?: return
        index = target
        loading = true
        message = null
        switcher = false
        scope.launch {
            when (val result = repo.resolveClip(clip.bvid)) {
                is Result.Ok -> {
                    controller.setLiveMode(false)
                    controller.play(result.value, 0L)
                    message = clip.title
                }
                is Result.Err -> {
                    message = "解析失败：${result.error.msg}，跳到下一条"
                    if (autoNext) {
                        delay(1_200)
                        advance()
                    } else {
                        controller.pause()
                    }
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        runCatching { rootFocus.requestFocus() }
        com.tvbox.app.data.casual.CasualGraph.radioSessionOrNull()?.stopForExternal()
        controller.setQuality(appSettings.qualityCap)
        controller.setAudioDelayMs(appSettings.audioDelayMs)
        controller.setLiveMode(false)
        playIndex(index)
    }

    LaunchedEffect(index) {
        if (!loading) message = current?.title
    }

    LaunchedEffect(state) {
        when (state) {
            is PlayerController.State.Ended -> if (autoNext) {
                delay(200)
                advance()
            }
            is PlayerController.State.Error -> if (autoNext && !loading) {
                delay(800)
                advance()
            }
            else -> Unit
        }
    }

    LaunchedEffect(hudVisible, state) {
        if (!hudVisible) {
            runCatching { rootFocus.requestFocus() }
            return@LaunchedEffect
        }
        delay(3_500)
        if (state is PlayerController.State.Playing) hudVisible = false
    }

    DisposableEffect(controller) {
        onDispose {
            PlayerBootstrap.release(controller)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent {
                hudVisible = true
                false
            },
    ) {
        VideoSurface(controller = controller, modifier = Modifier.fillMaxSize())
        PlaybackHud(
            controller = controller,
            visible = hudVisible,
            title = current?.title ?: "随便看看",
            subtitle = current?.owner,
            onExit = onBack,
        )

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TvLabel("正在解析播放地址…", dimens.rowTitle, Color.White)
            }
        }

        if (hudVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 78.dp, end = 40.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TvButton(
                    text = if (autoNext) "连播：开" else "连播：关",
                    onClick = { autoNext = !autoNext },
                    primary = autoNext,
                )
                TvButton("下一条", onClick = { advance() })
                TvButton("回到第一条", onClick = { playIndex(0) })
            }
            message?.let {
                TvLabel(
                    text = it,
                    fontSize = dimens.caption,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(40.dp),
                    maxLines = 2,
                )
            }
        }

        if (switcher) {
            TvLabel("切换中…", dimens.body, Color.White, modifier = Modifier.align(Alignment.Center))
        }
    }
}
