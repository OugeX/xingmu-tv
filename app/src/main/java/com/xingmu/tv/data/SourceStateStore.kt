package com.xingmu.tv.data

import android.content.Context

/**
 * 源的用户态覆盖（启用/优先级/删除）落 SharedPreferences。
 * 内置源本身只读 assets，任何用户操作都只写覆盖层，重新安装即可恢复。
 */
class SourceStateStore(context: Context) {

    private val prefs = context.getSharedPreferences("tvbox_sources", Context.MODE_PRIVATE)

    fun enabledOverride(id: String): Boolean? =
        if (prefs.contains("enabled.$id")) prefs.getBoolean("enabled.$id", true) else null

    fun priorityOverride(id: String): Int? =
        if (prefs.contains("priority.$id")) prefs.getInt("priority.$id", 100) else null

    fun isRemoved(id: String): Boolean = prefs.getBoolean("removed.$id", false)

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled.$id", enabled).apply()
    }

    fun setPriority(id: String, priority: Int) {
        prefs.edit().putInt("priority.$id", priority.coerceIn(0, 1000)).apply()
    }

    fun remove(id: String) {
        prefs.edit().putBoolean("removed.$id", true).apply()
    }

    fun restore(id: String) {
        prefs.edit().remove("removed.$id").apply()
    }

    fun apply(sources: List<SourceConfig>): List<SourceConfig> = sources.asSequence()
        .filterNot { isRemoved(it.id) }
        .map { source ->
            source.copy(
                enabled = enabledOverride(source.id) ?: source.enabled,
                priority = priorityOverride(source.id) ?: source.priority,
            )
        }
        .sortedBy { it.priority }
        .toList()
}
