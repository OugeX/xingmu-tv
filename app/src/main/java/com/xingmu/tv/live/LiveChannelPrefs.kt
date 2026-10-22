package com.xingmu.tv.live

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 直播「收藏 / 最近」本地存储（t30，docs/LIVE_CATEGORY.md）。
 *
 * 只存频道 id 字符串，不复制频道对象：频道数据每次由 live_seed + live_safe 重新生成，
 * 用 id 关联可以在源更新后仍然正确（消失的台自然从收藏/最近里不显示）。
 *
 * 用 [StateFlow] 暴露，UI 直接 collectAsState —— 收藏/最近变化时分组 Tab 计数与网格同步刷新。
 */
class LiveChannelPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("tvbox_live_channels", Context.MODE_PRIVATE)

    private val _favorites = MutableStateFlow(loadFavorites())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _recents = MutableStateFlow(loadRecents())
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    fun isFavorite(channelId: String): Boolean = _favorites.value.contains(channelId)

    /** @return true = 已加入收藏；false = 已取消。 */
    fun toggleFavorite(channelId: String): Boolean {
        val current = _favorites.value.toMutableSet()
        val added = if (!current.remove(channelId)) {
            current.add(channelId)
            true
        } else {
            false
        }
        _favorites.value = current
        // StringSet 不能原地改，写回新集合
        prefs.edit().putStringSet(KEY_FAVORITES, HashSet(current)).apply()
        return added
    }

    /** 播放成功（首帧）时调用：置顶 + 保留 [RECENT_MAX] 条。 */
    fun touchRecent(channelId: String) {
        val current = _recents.value
        if (current.firstOrNull() == channelId) return
        val next = ArrayList<String>(RECENT_MAX)
        next.add(channelId)
        for (id in current) {
            if (id != channelId && next.size < RECENT_MAX) next.add(id)
        }
        _recents.value = next
        prefs.edit().putString(KEY_RECENTS, next.joinToString(",")).apply()
    }

    fun clearFavorites() {
        _favorites.value = emptySet()
        prefs.edit().remove(KEY_FAVORITES).apply()
    }

    fun clearRecents() {
        _recents.value = emptyList()
        prefs.edit().remove(KEY_RECENTS).apply()
    }

    private fun loadFavorites(): Set<String> =
        prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toSet()

    private fun loadRecents(): List<String> =
        prefs.getString(KEY_RECENTS, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(RECENT_MAX)
            ?: emptyList()

    companion object {
        /** 「最近」保留条数（docs/LIVE_CATEGORY.md §1）。 */
        const val RECENT_MAX = 10

        private const val KEY_FAVORITES = "favorites"
        private const val KEY_RECENTS = "recents"
    }
}
