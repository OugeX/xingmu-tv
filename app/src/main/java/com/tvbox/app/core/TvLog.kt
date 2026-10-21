package com.tvbox.app.core

import android.util.Log

/**
 * 统一日志入口：debug 包全量输出，release 只保留 W/E，避免低端机 IO 抖动。
 * 第三方库（OkHttp/Media3）的日志不要绕过这里。
 */
object TvLog {

    private const val TAG_PREFIX = "TVBox/"

    @PublishedApi
    internal val verbose: Boolean = AppBuild.DEBUG

    fun d(tag: String, msg: String) {
        if (verbose) Log.d(TAG_PREFIX + tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (verbose) Log.i(TAG_PREFIX + tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(TAG_PREFIX + tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(TAG_PREFIX + tag, msg, tr)
    }
}

/** 构建类型标记（不依赖 BuildConfig，保持 buildConfig=false）。 */
internal object AppBuild {
    val DEBUG: Boolean = run {
        // debug 包的可调试标记：ApplicationInfo.FLAG_DEBUGGABLE，由系统在安装后确定
        try {
            val cls = Class.forName("android.app.ActivityThread")
            val app = cls.getMethod("currentApplication").invoke(null) as? android.app.Application
            app != null && (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (t: Throwable) {
            true
        }
    }
}
