package com.tvbox.app.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/**
 * 沉浸式全屏（UI_SPEC §3.5/§3.6 硬要求）：只改 Window flags / insets，**不触碰 player 与 Surface**，
 * 因此播放中进出全屏不会黑屏 1–2 秒。
 *
 * - API 30+：`WindowInsetsController.hide(systemBars)` + swipe 临时呼出；
 * - API 23–29：`SYSTEM_UI_FLAG_IMMERSIVE_STICKY | FULLSCREEN | HIDE_NAVIGATION | LAYOUT_*`；
 * - 计数式恢复：多个页面/多个 `FullscreenEffect` 同时存在时，最后一个退出才恢复系统栏。
 */
object PlaybackFullscreen {

    private data class State(
        val systemUiVisibility: Int,
        val decorFitsSystemWindows: Boolean,
        val keepScreenOn: Boolean,
    )

    private val counts = WeakHashMap<Activity, Int>()
    private val saved = WeakHashMap<Activity, State>()

    fun enter(context: Context) {
        val activity = context.findActivity() ?: return
        val count = counts[activity] ?: 0
        counts[activity] = count + 1
        if (count > 0) return
        val window = activity.window
        saved[activity] = State(
            systemUiVisibility = window.decorView.systemUiVisibility,
            decorFitsSystemWindows = true,
            keepScreenOn = (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    fun exit(context: Context) {
        val activity = context.findActivity() ?: return
        val count = counts[activity] ?: return
        if (count > 1) {
            counts[activity] = count - 1
            return
        }
        counts.remove(activity)
        val previous = saved.remove(activity) ?: return
        val window = activity.window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(previous.decorFitsSystemWindows)
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = previous.systemUiVisibility
        }
        if (!previous.keepScreenOn) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}

/**
 * 放在播放/直播页面根部（或 [VideoSurface] 内部）即可：进入组合时全屏，退出时恢复。
 * 只改 Window，不会触发播放器重建。
 */
@Composable
fun FullscreenEffect(enabled: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(context, enabled) {
        if (enabled) PlaybackFullscreen.enter(context)
        onDispose { if (enabled) PlaybackFullscreen.exit(context) }
    }
}
