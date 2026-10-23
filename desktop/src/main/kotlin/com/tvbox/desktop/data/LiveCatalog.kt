package com.tvbox.desktop.data

import org.json.JSONObject

enum class LiveCategoryKind { HD, SEED, FAVORITES, RECENT }

data class LiveCategory(
    val id: String,
    val name: String,
    val kind: LiveCategoryKind,
    val channelIds: List<String>,
    val count: Int,
)

/**
 * 直播分组目录：顺序与 Android 主线一致 —
 * 高清专区（live_hd.json 的 114 台）→ 央视（cctv）→ 卫视（satellite）→ 地方（local）→ 收藏 → 最近。
 */
class LiveCatalog(
    private val channelsById: Map<String, LiveChannel>,
    val categories: List<LiveCategory>,
) {
    fun category(id: String?): LiveCategory? = categories.firstOrNull { it.id == id }

    fun channelsFor(categoryId: String?): List<LiveChannel> {
        val category = category(categoryId) ?: return emptyList()
        return category.channelIds.mapNotNull { channelsById[it] }
    }

    fun channel(id: String): LiveChannel? = channelsById[id]

    val allChannels: List<LiveChannel> get() = channelsById.values.toList()
}

object LiveHd {
    fun key(name: String, group: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), "") + "|" + group.trim()

    fun parseKeys(text: String): Set<String> {
        val root = JSONObject(text)
        val array = root.optJSONArray("channels") ?: return emptySet()
        val keys = HashSet<String>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name.isBlank()) continue
            keys.add(key(name, obj.optString("group").trim()))
        }
        return keys
    }

    /** 只读仓库 assets/live_hd.json（打包后回退 jar 内兜底），不在源码树复制。 */
    fun loadKeys(paths: AppPaths): Set<String> {
        val text = paths.readBundled("live_hd.json")?.first ?: return emptySet()
        return runCatching { parseKeys(text) }.getOrDefault(emptySet())
    }
}

object LiveCatalogBuilder {

    fun build(
        snapshot: LiveSnapshot,
        hdKeys: Set<String>,
        favoriteIds: List<String>,
        recentIds: List<String>,
    ): LiveCatalog {
        val channelsById = LinkedHashMap<String, LiveChannel>()
        snapshot.channels.forEach { channelsById[it.id] = it }

        val categories = mutableListOf<LiveCategory>()

        val hdIds = snapshot.channels
            .filter { LiveHd.key(it.name, it.group) in hdKeys }
            .map { it.id }
            .distinct()
        categories += LiveCategory("hd", "高清专区", LiveCategoryKind.HD, hdIds, hdIds.size)

        snapshot.groups.sortedBy { it.order }.forEach { group ->
            val ids = snapshot.channels.filter { it.groupId == group.id }.map { it.id }
            categories += LiveCategory(group.id, group.name, LiveCategoryKind.SEED, ids, ids.size)
        }

        categories += LiveCategory(
            id = "favorites",
            name = "收藏",
            kind = LiveCategoryKind.FAVORITES,
            channelIds = favoriteIds.distinct(),
            count = favoriteIds.distinct().size,
        )
        categories += LiveCategory(
            id = "recent",
            name = "最近",
            kind = LiveCategoryKind.RECENT,
            channelIds = recentIds.distinct(),
            count = recentIds.distinct().size,
        )

        return LiveCatalog(channelsById, categories)
    }
}

object LivePaging {
    const val LOCAL_PAGE_SIZE = 60

    fun pageCount(total: Int, pageSize: Int = LOCAL_PAGE_SIZE): Int {
        if (total <= 0) return 1
        return (total + pageSize - 1) / pageSize
    }

    fun <T> page(items: List<T>, page: Int, pageSize: Int = LOCAL_PAGE_SIZE): List<T> {
        if (items.isEmpty()) return emptyList()
        val safePage = page.coerceIn(0, pageCount(items.size, pageSize) - 1)
        return items.drop(safePage * pageSize).take(pageSize)
    }
}
