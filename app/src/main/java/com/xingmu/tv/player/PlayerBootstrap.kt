package com.xingmu.tv.player

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.data.AppServices

/**
 * 播放器接入点（t4 播放页/直播页调用）。
 *
 * PLAYER_PERF §2 硬约束：**全应用只允许一个 ExoPlayer**，`release()` 只在 Activity onDestroy。
 * 因此页面离开时 [release] 只做 pause（保留播放器/Surface，回来看秒开），真正释放由
 * Application.ActivityLifecycleCallbacks 在 Activity destroyed 时触发。
 *
 * 播放页：
 * ```
 * val controller = remember { PlayerBootstrap.createAndRegister(context) }
 * DisposableEffect(controller) { onDispose { PlayerBootstrap.release(controller) } }
 * ```
 */
object PlayerBootstrap {

    private const val TAG = "PlayerBootstrap"

    @Volatile
    private var lifecycleRegistered = false

    /** 只跟随最后一个 Activity：relaunch 时旧 Activity 的 onDestroy 不能把新实例的 player 释放掉。 */
    @Volatile
    private var ownerActivity: java.lang.ref.WeakReference<Activity>? = null

    @Synchronized
    fun createAndRegister(context: Context): PlayerControllerImpl {
        registerLifecycle(context)
        context.findActivity()?.let { ownerActivity = java.lang.ref.WeakReference(it) }
        val existing = AppServices.playerOrNull()
        if (existing is PlayerControllerImpl && !existing.isReleased) return existing
        val controller = PlayerControllerImpl(context)
        AppServices.registerPlayer(controller)
        TvLog.i(TAG, "single player created")
        return controller
    }

    /**
     * 页面离开：只暂停并解绑画面（VideoSurface 自己会 setPlayer(null)），不 release。
     * 回到播放页复用同一实例，避免 1GB 设备反复 codec init 造成 1-2s 黑屏。
     */
    @Synchronized
    fun release(controller: PlayerControllerImpl) {
        controller.pause()
        TvLog.i(TAG, "page released -> player parked (release on Activity onDestroy)")
    }

    /** 真正释放（Activity onDestroy / 进程退出）。 */
    @Synchronized
    fun destroy(controller: PlayerControllerImpl) {
        if (AppServices.playerOrNull() === controller) {
            AppServices.registerPlayer(null)
        }
        PlayerSurfaceCache.clear()
        controller.release()
        TvLog.i(TAG, "player destroyed")
    }

    private fun registerLifecycle(context: Context) {
        if (lifecycleRegistered) return
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(activity: Activity) {
                if (ownerActivity?.get() !== activity) {
                    TvLog.d(TAG, "ignore old activity destroy")
                    return
                }
                ownerActivity = null
                val controller = AppServices.playerOrNull()
                if (controller is PlayerControllerImpl) destroy(controller)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                ownerActivity = java.lang.ref.WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        lifecycleRegistered = true
        TvLog.i(TAG, "activity lifecycle registered")
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is android.content.ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
