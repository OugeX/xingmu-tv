package com.tvbox.app.player

import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * SurfaceView 只创建一次（PLAYER_PERF §2）：直播/点播/切台都复用同一个 PlayerView，
 * 页面切换只 `player = null` 解绑，不销毁 View/Surface。
 *
 * 使用 applicationContext 创建，避免缓存 View 持有 Activity 造成泄漏。
 */
object PlayerSurfaceCache {

    private var cached: PlayerView? = null

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun obtain(context: android.content.Context): PlayerView {
        val existing = cached
        if (existing != null && existing.parent == null) return existing
        val view = PlayerView(context.applicationContext).apply {
            setUseController(false)
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setResizeMode(AspectMode.FIT.resizeMode)
            setKeepContentOnPlayerReset(true)
            setShutterBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        cached = view
        return view
    }

    fun clear() {
        cached?.player = null
        cached = null
    }
}

/**
 * 视频输出：SurfaceView 直出（PlayerView 默认 SurfaceView，比 TextureView 少一次 GPU 拷贝）。
 *
 * - `setUseController(false)`：控制器完全由 Compose 覆盖层（[PlaybackHud] / t4 自绘）负责；
 * - `setShowBuffering(NEVER)`：缓冲提示用静态文字，避免内置转圈 View 频繁重绘；
 * - `generation`：解码器故障重建播放器后重新绑定 Surface，避免“黑屏但声音正常”。
 *
 * 用法（由 t4 播放页调用）：
 * ```
 * val controller = remember { PlayerControllerImpl(context) }
 * DisposableEffect(Unit) { onDispose { controller.release() } }
 * Box { VideoSurface(controller, Modifier.fillMaxSize()); PlaybackHud(controller, visible) }
 * ```
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun VideoSurface(
    controller: PlayerControllerImpl,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val generation by controller.generation.collectAsState()
    val info by controller.playbackInfo.collectAsState()

    // 播放即沉浸式全屏（只改 Window，不重建 player/surface）
    FullscreenEffect(enabled = true)

    // 播放器重建后 generation 变化 → remember 重新取 exoPlayer；已 release 时不再重建
    val currentPlayer = remember(generation) {
        if (controller.isReleased) null else controller.exoPlayer
    }

    val playerView = remember { PlayerSurfaceCache.obtain(context) }

    AndroidView(
        factory = {
            playerView.apply {
                player = currentPlayer
                (videoSurfaceView as? android.view.SurfaceView)?.let { controller.bindSurfaceView(it) }
            }
        },
        update = { view ->
            if (view.player !== currentPlayer) {
                view.player = currentPlayer
                (view.videoSurfaceView as? android.view.SurfaceView)
                    ?.let { controller.bindSurfaceView(it) }
            }
            view.setResizeMode(info.aspect.resizeMode)
        },
        modifier = modifier,
    )

    DisposableEffect(controller) {
        onDispose {
            // 先解绑再让页面 release，避免 Surface 先于 codec 销毁导致崩溃
            playerView.player = null
        }
    }
}
