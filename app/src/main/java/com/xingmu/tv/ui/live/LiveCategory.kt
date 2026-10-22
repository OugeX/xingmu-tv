package com.xingmu.tv.ui.live

import com.xingmu.tv.live.LiveChannel
import com.xingmu.tv.live.LiveState

/**
 * 直播分组视图的纯函数层（t30，docs/LIVE_CATEGORY.md）。
 *
 * 放在 UI 包而不是 LiveController：分组/分页/数字过滤都是**展示逻辑**，
 * 不该进播放器状态机（切分组不触发播放、不重建播放器就是要靠这一点）。
 */
data class ChannelBucket(
    val id: String,
    val name: String,
    val channels: List<LiveChannel>,
    /** 虚拟组（高清专区/收藏/最近），不来自 live_seed 的 group_id */
    val virtual: Boolean = false,
)

/** 频道格子右侧的小标签。 */
data class ChannelTag(val text: String, val danger: Boolean)

object LiveCategory {

    const val ID_HD = "hd"
    const val ID_FAVORITES = "favorites"
    const val ID_RECENT = "recent"

    /** 组内超过该数量启用分页签（docs/LIVE_CATEGORY.md §2）。 */
    const val PAGE_SIZE = 60

    /**
     * 分组顺序：高清专区 → seed 分组（央视/卫视/地方/港澳台/其他）→ 收藏 → 最近。
     *
     * [state] 里传入的频道已经是「安全模式过滤后」的列表（LiveController.visibleChannels），
     * 所以这里的计数天然满足「安全模式只算 has_safe=true」。
     */
    fun buckets(
        state: LiveState,
        favorites: Set<String>,
        recents: List<String>,
    ): List<ChannelBucket> {
        val result = ArrayList<ChannelBucket>(state.groups.size + 2)
        for (group in state.groups.sortedBy { it.order }) {
            val channels = state.groupedChannels[group.name].orEmpty()
            if (channels.isEmpty()) continue
            result += ChannelBucket(
                id = group.id,
                name = group.name,
                channels = channels,
                virtual = group.id == ID_HD,
            )
        }
        // 收藏 / 最近 即使为空也保留（用户能看到"还没收藏过"），但排在最后
        val byId = state.channels.associateBy { it.id }
        val favChannels = favorites.mapNotNull { byId[it] }
            .sortedBy { it.number }
        result += ChannelBucket(ID_FAVORITES, "收藏", favChannels, virtual = true)
        val recentChannels = recents.mapNotNull { byId[it] }
        result += ChannelBucket(ID_RECENT, "最近", recentChannels, virtual = true)
        return result
    }

    fun pageCount(total: Int): Int = if (total <= PAGE_SIZE) 1 else (total + PAGE_SIZE - 1) / PAGE_SIZE

    fun pageOf(channels: List<LiveChannel>, page: Int): List<LiveChannel> {
        if (channels.size <= PAGE_SIZE) return channels
        val safePage = page.coerceIn(0, pageCount(channels.size) - 1)
        val from = safePage * PAGE_SIZE
        val to = minOf(from + PAGE_SIZE, channels.size)
        return if (from >= to) emptyList() else channels.subList(from, to)
    }

    fun pageLabel(channels: List<LiveChannel>, page: Int): String {
        val from = page * PAGE_SIZE + 1
        val to = minOf((page + 1) * PAGE_SIZE, channels.size)
        return "$from-$to"
    }

    /**
     * 数字键直达：台号前缀匹配。输入 `5` 命中 5 / 50 / 51…，输入 `05` 只命中 5。
     * 匹配结果按台号升序，UI 聚焦第一个。
     */
    fun filterByNumber(channels: List<LiveChannel>, entry: String): List<LiveChannel> {
        val digits = entry.filter { it.isDigit() }
        if (digits.isEmpty()) return channels
        return channels.filter { channel ->
            val number = channel.number.toString()
            number.startsWith(digits) || "%03d".format(channel.number).startsWith(digits)
        }.sortedBy { it.number }
    }

    fun bucketContaining(buckets: List<ChannelBucket>, channel: LiveChannel?): ChannelBucket? {
        channel ?: return null
        return buckets.firstOrNull { bucket -> bucket.channels.any { it.id == channel.id } }
    }

    fun isSelectable(bucket: ChannelBucket): Boolean = bucket.channels.isNotEmpty()

    /** 右上角标签：可能黑屏 > 安全档清晰度 > 线路数。 */
    fun tagOf(channel: LiveChannel, showAllUnsafe: Boolean): ChannelTag? = when {
        showAllUnsafe && channel.safe == false -> ChannelTag("可能黑屏", danger = true)
        channel.safeHeight > 0 -> ChannelTag("${channel.safeHeight}p", danger = false)
        else -> when {
            channel.quality != null -> ChannelTag(channel.quality!!, danger = false)
            channel.lines.isNotEmpty() -> ChannelTag("${channel.lines.size}线", danger = false)
            else -> null
        }
    }
}
