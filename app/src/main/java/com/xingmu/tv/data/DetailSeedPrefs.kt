package com.xingmu.tv.data

import android.content.Context

/**
 * 预置详情包的开关与进度持久化（t27）。
 *
 * 开关和设置页共用 `tvbox_settings` 里的 `seed_details`（默认开），
 * 这样 UI 的 SettingsStore 与数据层的 Sync 读的是同一个键，不会出现「设置页关了还在下载」。
 */
class DetailSeedPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** 已导入的包版本（如 "v1"）；用于避免重复导入。 */
    fun importedVersion(): String? = prefs.getString(KEY_IMPORTED_VERSION, null)

    fun markImported(version: String, count: Int) {
        prefs.edit()
            .putString(KEY_IMPORTED_VERSION, version)
            .putInt(KEY_IMPORTED_COUNT, count)
            .putLong(KEY_IMPORTED_AT, System.currentTimeMillis())
            .putString(KEY_LAST_ERROR, null)
            .apply()
    }

    fun importedCount(): Int = prefs.getInt(KEY_IMPORTED_COUNT, 0)

    fun importedAt(): Long = prefs.getLong(KEY_IMPORTED_AT, 0L)

    fun lastAttemptAt(): Long = prefs.getLong(KEY_LAST_ATTEMPT_AT, 0L)

    fun markAttempt() {
        prefs.edit().putLong(KEY_LAST_ATTEMPT_AT, System.currentTimeMillis()).apply()
    }

    fun lastError(): String? = prefs.getString(KEY_LAST_ERROR, null)

    fun markError(message: String?) {
        prefs.edit().putString(KEY_LAST_ERROR, message).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "tvbox_settings"
        const val KEY_ENABLED = "seed_details"
        private const val KEY_IMPORTED_VERSION = "seed_imported_version"
        private const val KEY_IMPORTED_COUNT = "seed_imported_count"
        private const val KEY_IMPORTED_AT = "seed_imported_at"
        private const val KEY_LAST_ATTEMPT_AT = "seed_last_attempt_at"
        private const val KEY_LAST_ERROR = "seed_last_error"
    }
}
