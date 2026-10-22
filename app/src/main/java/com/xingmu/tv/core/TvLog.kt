package com.xingmu.tv.core

import android.util.Log

/**
 * 统一日志入口：debug 包全量输出，release 只保留 W/E，避免低端机 IO 抖动。
 * 第三方库（OkHttp/Media3）的日志不要绕过这里。
 */
object TvLog {

    private const val TAG_PREFIX = "TVBox/"

    /**
     * 是否输出 debug 日志。
     *
     * R8 release 包里 [AppBuild.DEBUG] 为 false，真机排障时会拿不到 `pick mime` / `PLAYER_EVT` 这类关键行。
     * 因此额外支持一个系统属性开关（issue #1 建议）：
     *   adb shell setprop debug.xingmu.log 1   # 打开
     *   adb shell setprop debug.xingmu.log 0   # 关闭（进程重启后生效）
     */
    @PublishedApi
    internal val verbose: Boolean = AppBuild.DEBUG || readProp("debug.xingmu.log") == "1"

    private fun readProp(key: String): String = try {
        val cls = Class.forName("android.os.SystemProperties")
        (cls.getMethod("get", String::class.java).invoke(null, key) as? String).orEmpty()
    } catch (t: Throwable) {
        ""
    }

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
