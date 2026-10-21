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
import com.tvbox.app.core.TvLog

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
        TvTextureView(context.applicationContext).apply {
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
            if (view is TvTextureView) {
                currentPlayer?.setVideoTextureView(view)
                controller.bindTextureView(view)
                view.setVideoGeometry(info.width, info.height, info.aspect)
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
private class TvTextureView(context: android.content.Context) : TextureView(context) {

    private var videoWidth = 0
    private var videoHeight = 0
    private var aspectMode = AspectMode.FIT

    fun setVideoGeometry(width: Int, height: Int, mode: AspectMode) {
        videoWidth = width
        videoHeight = height
        aspectMode = mode
        applyAspectTransform()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyAspectTransform()
    }

    /**
     * 按「视频宽高比 vs 视图宽高比」计算变换，而不是用像素比。
     *
     * 旧实现的 bug：`scale = min(viewW/videoW, viewH/videoH)` 然后统一缩放，
     * 在 4:3 内容 / 非 16:9 视图上会退化成 identity（画面被拉伸或截断），
     * 真机表现为「左边一条黑、画面被裁」。
     *
     * 正确做法：先算目标显示尺寸 = video * k（FIT 取 contain，ZOOM 取 cover，FILL 直接铺满），
     * 再换算成视图空间的 sx/sy（TextureView 的 texture 默认就被拉伸到整个视图）。
     */
    private fun applyAspectTransform() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val viewWidth = width.toFloat().takeIf { it > 0f } ?: return
        val viewHeight = height.toFloat().takeIf { it > 0f } ?: return
        val vw = videoWidth.toFloat()
        val vh = videoHeight.toFloat()

        val k = when (aspectMode) {
            AspectMode.FIT -> minOf(viewWidth / vw, viewHeight / vh)
            AspectMode.FILL -> 0f                       // 拉伸铺满：不缩放，直接用默认拉伸
            AspectMode.ZOOM -> maxOf(viewWidth / vw, viewHeight / vh)
        }
        val sx: Float
        val sy: Float
        val dx: Float
        val dy: Float
        if (aspectMode == AspectMode.FILL) {
            sx = 1f; sy = 1f; dx = 0f; dy = 0f
        } else {
            val dispW = vw * k
            val dispH = vh * k
            sx = dispW / viewWidth
            sy = dispH / viewHeight
            dx = (viewWidth - dispW) / 2f
            dy = (viewHeight - dispH) / 2f
        }
        val matrix = Matrix().apply {
            setScale(sx, sy)
            postTranslate(dx, dy)
        }
        setTransform(matrix)
        TvLog.i(
            "TextureView",
            "transform mode=${aspectMode.label} video=${videoWidth}x$videoHeight view=${width}x$height sx=$sx sy=$sy dx=$dx dy=$dy",
        )
    }
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
