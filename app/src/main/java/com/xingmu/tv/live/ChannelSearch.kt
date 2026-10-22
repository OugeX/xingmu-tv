package com.xingmu.tv.live

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * 直播「搜台」匹配器（t30，docs/LIVE_CATEGORY.md §3）。
 *
 * 复用搜索页同一套拼音资源（`assets/pinyin_initials.json` / `pinyin_full.json`），
 * 但频道是 [LiveChannel] 而不是 [com.xingmu.tv.data.VodSummary]，PinyinIndex 又只吃点播数据，
 * 所以这里做一个很小的频道专用匹配：
 *
 *  - 中文名包含（`央视`）
 *  - 首拼包含（`hnws` → 湖南卫视）
 *  - 全拼前缀/包含（`hunan` → 湖南卫视）
 *
 * 频道只有 200+ 条，字典懒加载 + 每台名缓存首拼/全拼，单次过滤是毫秒级。
 */
class ChannelSearch(context: Context) {

    private val appContext = context.applicationContext

    private val initialsMap: Map<String, String> by lazy { readMap(ASSET_INITIALS) }
    private val fullMap: Map<String, String> by lazy { readMap(ASSET_FULL) }

    /** 频道名 → (首拼, 全拼)，避免每次过滤重复拼。 */
    private val variantCache = HashMap<String, Pair<String, String>>(320)

    fun filter(channels: List<LiveChannel>, rawQuery: String): List<LiveChannel> {
        val query = normalize(rawQuery)
        if (query.isEmpty()) return channels
        val scored = ArrayList<Pair<LiveChannel, Int>>(channels.size)
        for (channel in channels) {
            val score = scoreOf(channel.name, query)
            if (score >= 0) scored.add(channel to score)
        }
        scored.sortWith(compareBy({ it.second }, { it.first.number }))
        return scored.map { it.first }
    }

    /** 返回 -1 = 不匹配；数值越小越靠前。 */
    private fun scoreOf(name: String, query: String): Int {
        val normName = normalize(name)
        if (normName.contains(query)) {
            return if (normName.startsWith(query)) 0 else 1
        }
        val (initials, full) = variantsOf(name)
        if (initials.isEmpty() && full.isEmpty()) return -1
        return when {
            initials == query -> 0
            initials.startsWith(query) -> 1
            full.startsWith(query) -> 2
            initials.contains(query) -> 3
            full.contains(query) -> 4
            else -> -1
        }
    }

    private fun variantsOf(name: String): Pair<String, String> {
        synchronized(variantCache) { variantCache[name] }?.let { return it }
        val initials = StringBuilder(name.length)
        val full = StringBuilder(name.length * 4)
        for (ch in name) {
            val key = ch.toString()
            if (isAsciiAlnum(ch)) {
                initials.append(ch.lowercaseChar())
                full.append(ch.lowercaseChar())
            } else if (isCjk(ch)) {
                val ini = initialsMap[key]
                if (!ini.isNullOrEmpty()) initials.append(ini.first().lowercaseChar()) else initials.append(ch)
                val f = fullMap[key]
                if (!f.isNullOrEmpty()) {
                    // 多音字形如 "chang|zhang"，取第一种（多数台名不涉及多音字）
                    full.append(f.substringBefore('|'))
                } else {
                    full.append(ch)
                }
            }
        }
        val pair = initials.toString() to full.toString()
        synchronized(variantCache) { variantCache[name] = pair }
        return pair
    }

    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase(Locale.ROOT)) {
            if (isAsciiAlnum(ch) || isCjk(ch)) sb.append(ch)
        }
        return sb.toString()
    }

    private fun isAsciiAlnum(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'

    private fun isCjk(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF

    private fun readMap(asset: String): Map<String, String> = runCatching {
        val text = appContext.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        val map = HashMap<String, String>(root.length() * 2)
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = root.optString(key)
        }
        map
    }.getOrElse {
        TvLogProxy.warn("ChannelSearch", "拼音资源缺失 $asset：搜台退化为名称匹配")
        emptyMap()
    }

    private companion object {
        const val ASSET_INITIALS = "pinyin_initials.json"
        const val ASSET_FULL = "pinyin_full.json"
    }
}

/** 避免 live 包直接依赖 TvLog 的签名细节（保持与其它 live 类一致的日志前缀）。 */
private object TvLogProxy {
    fun warn(tag: String, message: String) {
        runCatching { com.xingmu.tv.core.TvLog.w(tag, message) }
    }
}
