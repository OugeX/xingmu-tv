package com.tvbox.app.player

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
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
 * 视频输出（PLAYER_PERF §2：进程内只创建一次，切台/切集不重建）。
 *
 * 两条渲染路径：
 * - **API ≤ 23（含 MStar 6A938 / LeTV Android 6）**：`TextureView` 直出。
 *   SurfaceView 在这类老平台播 1080p 会持续 `GLConsumer GL 0x500` 黑屏；
 *   TextureView 走独立纹理路径，能出画面（代价是多一次 GPU 拷贝）。
 * - **API ≥ 24**：`PlayerView` 默认 SurfaceView（性能优先）。
 *
 * 切源只 `setMediaItem`，不换 View；播放器重建时 generation 变化只重新绑定 surface。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun VideoSurface(
    controller: PlayerControllerImpl,
    modifier: Modifier = Modifier,
) {
    FullscreenEffect(enabled = true)
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
        TextureVideoSurface(controller, modifier)
    } else {
        SurfaceVideoSurface(controller, modifier)
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun TextureVideoSurface(
    controller: PlayerControllerImpl,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val generation by controller.generation.collectAsState()
    val info by controller.playbackInfo.collectAsState()
    val currentPlayer = remember(generation) {
        if (controller.isReleased) null else controller.exoPlayer
    }
    val textureView = remember {
        TextureView(context.applicationContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    AndroidView(
        factory = {
            textureView.apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        controller.bindTextureView(textureView)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        controller.notifyTextureUpdated()
                    }
                }
                currentPlayer?.setVideoTextureView(this)
                controller.bindTextureView(this)
            }
        },
        update = { view ->
            if (view === textureView) {
                currentPlayer?.setVideoTextureView(view)
                controller.bindTextureView(view)
                applyAspectTransform(view, info.width, info.height, info.aspect)
            }
        },
        modifier = modifier,
    )

    DisposableEffect(controller) {
        onDispose {
            currentPlayer?.clearVideoSurface()
            controller.bindTextureView(null)
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun SurfaceVideoSurface(
    controller: PlayerControllerImpl,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val generation by controller.generation.collectAsState()
    val info by controller.playbackInfo.collectAsState()
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
        onDispose { playerView.player = null }
    }
}

/** TextureView 的 FIT/FILL/ZOOM 变换（视频多余区域留黑边，不拉伸）。 */
private fun applyAspectTransform(
    view: TextureView,
    videoWidth: Int,
    videoHeight: Int,
    aspect: AspectMode,
) {
    if (videoWidth <= 0 || videoHeight <= 0) return
    val viewWidth = view.width.toFloat().takeIf { it > 0f } ?: return
    val viewHeight = view.height.toFloat().takeIf { it > 0f } ?: return
    val sx = viewWidth / videoWidth
    val sy = viewHeight / videoHeight
    val scale = when (aspect) {
        AspectMode.FIT -> minOf(sx, sy)
        AspectMode.FILL, AspectMode.ZOOM -> maxOf(sx, sy)
    }
    val matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate((viewWidth - videoWidth * scale) / 2f, (viewHeight - videoHeight * scale) / 2f)
    }
    view.setTransform(matrix)
}

/** 仅 API≥24 使用：SurfaceView 版 PlayerView 单例缓存。 */
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
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
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
