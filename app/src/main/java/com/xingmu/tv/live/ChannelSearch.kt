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
        val initials = initialsOf(name)
        if (initials.isEmpty()) return -1
        return when {
            initials == query -> 0
            initials.startsWith(query) -> 1
            // 全拼要按「多音字任一读音」逐字消费：湖南卫视 = hu+n(an)+wei+shi，只拼 hu+na 会匹配不到 hunan
            matchesFullPinyin(name, query) -> 2
            initials.contains(query) -> 3
            else -> -1
        }
    }

    /** 首拼：每个汉字取第一个读音的首字母（湖南卫视 → hnws）。 */
    private fun initialsOf(name: String): String {
        val sb = StringBuilder(name.length)
        for (ch in name) {
            when {
                isAsciiAlnum(ch) -> sb.append(ch.lowercaseChar())
                isCjk(ch) -> {
                    val ini = initialsMap[ch.toString()]
                    if (!ini.isNullOrEmpty()) sb.append(ini.first().lowercaseChar())
                }
            }
        }
        return sb.toString()
    }

    /**
     * 全拼前缀匹配：从台名第 0 个字开始逐个「吃掉」query 的字符。
     * 每个汉字可以吃掉它任一读音的前 k 个字母（多音字如 长 chang/zhang 都算），
     * 只要能把 query 吃完就算命中（等价于 PinyinIndex 的多变体展开，但不用提前生成组合）。
     */
    private fun matchesFullPinyin(name: String, query: String): Boolean {
        fun walk(nameIndex: Int, queryIndex: Int): Boolean {
            if (queryIndex >= query.length) return true
            if (nameIndex >= name.length) return false
            val ch = name[nameIndex]
            if (isAsciiAlnum(ch)) {
                return query[queryIndex] == ch.lowercaseChar() && walk(nameIndex + 1, queryIndex + 1)
            }
            if (!isCjk(ch)) {
                // 空格/标点跳过，不消费 query
                return walk(nameIndex + 1, queryIndex)
            }
            val variants = fullMap[ch.toString()]?.split('|')?.filter { it.isNotEmpty() }
                ?: return walk(nameIndex + 1, queryIndex + 1)
            for (variant in variants) {
                val max = minOf(variant.length, query.length - queryIndex)
                for (len in max downTo 0) {
                    if (query.regionMatches(queryIndex, variant, 0, len) && walk(nameIndex + 1, queryIndex + len)) {
                        return true
                    }
                }
            }
            return false
        }
        return walk(0, 0)
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
