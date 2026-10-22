package com.xingmu.tv.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs

/** 更新器持久化（只存检查时间，避免每次冷启动都打 GitHub）。 */
interface UpdatePreferences {
    var lastCheckAt: Long
}

class SharedPrefsUpdatePreferences(context: Context) : UpdatePreferences {
    private val prefs = context.applicationContext.getSharedPreferences("tvbox_update", Context.MODE_PRIVATE)

    override var lastCheckAt: Long
        get() = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_CHECK_AT, value).apply()
        }

    private companion object {
        const val KEY_LAST_CHECK_AT = "last_check_at"
    }
}

/** 从 PackageManager 读版本（buildConfig 关闭，禁止引 BuildConfig）。 */
object AppVersion {

    fun name(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "0.0.0" }

    fun code(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }.getOrDefault(0L)

    fun display(context: Context): String = "${name(context)} (${code(context)})"
}

/** 网络与存储探测（更新器只在 Wi-Fi 自动检查，见 UPDATE_SPEC §1）。 */
object UpdateEnvironment {

    fun isWifi(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // API 23+：NetworkCapabilities 判定（activeNetwork 需要 23）
            val network = manager.activeNetwork ?: return false
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            @Suppress("DEPRECATION")
            val info = manager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            info.type == ConnectivityManager.TYPE_WIFI
        }
    }.getOrDefault(false)

    /** /data 可用空间；读取失败返回 -1（按"不紧张"处理）。 */
    fun freeBytes(context: Context): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(-1L)

    /** API 26+ 需要「安装未知来源」授权；以下版本直接允许。 */
    fun canInstallPackages(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        return runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    }
}
