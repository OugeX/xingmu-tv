package com.xingmu.tv.ui

import android.content.Context

/**
 * t29：收藏（本地 SharedPreferences，跨重启保留）。
 *
 * 只存 `sourceId|vodId` 键，避免为收藏再开一张表；详情页按钮用于快速标记，
 * 「更多」面板里展示数量与清空入口。
 */
class FavoritesStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("tvbox_favorites", Context.MODE_PRIVATE)

    fun isFavorite(sourceId: String, vodId: String): Boolean =
        prefs.getStringSet(KEY, emptySet()).orEmpty().contains(key(sourceId, vodId))

    fun toggle(sourceId: String, vodId: String): Boolean {
        val current = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        val id = key(sourceId, vodId)
        val added = if (current.contains(id)) {
            current.remove(id)
            false
        } else {
            current.add(id)
            true
        }
        prefs.edit().putStringSet(KEY, current).apply()
        return added
    }

    fun count(): Int = prefs.getStringSet(KEY, emptySet()).orEmpty().size

    private fun key(sourceId: String, vodId: String): String = "$sourceId|$vodId"

    private companion object {
        const val KEY = "favorites"
    }
}
