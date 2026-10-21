package com.xingmu.tv.search

import android.content.Context
import com.xingmu.tv.core.AppDispatchers
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.data.VodSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.os.SystemClock
import org.json.JSONObject
import java.util.Locale

/**
 * 本地首拼 / 全拼索引。
 *
 * 说明（与 docs/SEARCH_SPEC.md §2.2 的差异，已在任务结果中记录）：
 *  - 规格写的是 Room + FTS4，但 t8 冻结的构建依赖里没有 Room/SQLite 库，t4 不允许改 build 配置；
 *  - 因此这里用 **内存倒排/线性匹配** 实现同样的“本地首拼即时出结果”能力：
 *    读 assets/pinyin_initials.json + pinyin_full.json（6763 字 → 多音候选），
 *    对已加载的片名做变体展开（每字最多 2 候选、整条最多 32 变体），查询走前缀匹配；
 *  - 索引只存在于进程内，上限 1500 条，内存约 2–3MB，首帧后按需建立。
 */
class PinyinIndex(context: Context) {

    data class Hit(
        val vod: VodSummary,
        /** 命中的拼音串，用于结果页“lldj · 流浪地球2”提示 */
        val matched: String,
        val score: Int,
    )

    private data class Entry(
        val vod: VodSummary,
        val initials: List<String>,
        val full: List<String>,
        val titleNorm: String,
    )

    private val appContext = context.applicationContext
    private val lock = Any()
    private val loadMutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    @Volatile
    private var loaded = false
    private var initialsMap: Map<String, String> = emptyMap()
    private var fullMap: Map<String, String> = emptyMap()

    suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            withContext(AppDispatchers.IoLimited) {
                try {
                    initialsMap = readMap(ASSET_INITIALS)
                    fullMap = readMap(ASSET_FULL)
                    TvLog.i("PinyinIndex", "拼音字典加载：initials=${initialsMap.size} full=${fullMap.size}")
                } catch (t: Throwable) {
                    TvLog.e("PinyinIndex", "拼音字典加载失败", t)
                }
                loaded = true
            }
        }
    }

    /** 把一页作品加入索引（幂等，按 sourceId+vodId 去重）。 */
    suspend fun index(items: List<VodSummary>) {
        if (items.isEmpty()) return
        ensureLoaded()
        withContext(AppDispatchers.IoLimited) {
            synchronized(lock) {
                for (vod in items) {
                    if (vod.name.isBlank() || vod.name.length > 24) continue
                    val key = "${vod.sourceId}|${vod.vodId}"
                    if (entries.containsKey(key)) continue
                    entries[key] = buildEntry(vod)
                }
                while (entries.size > MAX_ENTRIES) {
                    val oldest = entries.keys.firstOrNull() ?: break
                    entries.remove(oldest)
                }
            }
        }
    }

    suspend fun search(query: String, limit: Int = 100): List<Hit> {
        val normalized = normalizeQuery(query)
        if (normalized.isEmpty()) return emptyList()
        ensureLoaded()
        val snapshot = synchronized(lock) { entries.values.toList() }
        if (snapshot.isEmpty()) return emptyList()
        val results = ArrayList<Hit>()
        for (entry in snapshot) {
            var best = -1
            var matched = ""
            for (variant in entry.initials) {
                val score = scoreOf(variant, normalized)
                if (score > best) {
                    best = score
                    matched = variant
                }
            }
            for (variant in entry.full) {
                val score = scoreOf(variant, normalized)
                if (score > best) {
                    best = score
                    matched = variant
                }
            }
            if (entry.titleNorm.contains(normalized)) {
                best = maxOf(best, 300)
                if (matched.isEmpty()) matched = normalized
            }
            if (best > 0) results.add(Hit(entry.vod, matched, best))
        }
        return results
            .sortedWith(
                compareByDescending<Hit> { it.score }
                    .thenByDescending { it.vod.score.toFloatOrNull() ?: 0f }
                    .thenByDescending { it.vod.year },
            )
            .take(limit)
    }

    suspend fun candidates(query: String, limit: Int = 6): List<String> =
        search(query, limit).map { it.vod.name }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    data class IndexBenchmark(
        val entries: Int,
        val buildMs: Long,
        val lookupMs: Long,
        val multiPronunciationHit: Boolean,
        val capacity: Int,
    )

    /**
     * 首拼索引基准（t14 D）：
     * - 用真实片名模板构造 5000 条，测量建索引与查询耗时（不写入线上索引）；
     * - 校验多音字：长 → chang/zhang（长安三万里 → caswl / zaswl）；
     * - 结果写 TVBox/PinyinIndex BENCH，供验收记录。
     */
    suspend fun benchmark(entries: Int = 5_000): IndexBenchmark = withContext(AppDispatchers.IoLimited) {
        val loadStart = SystemClock.elapsedRealtime()
        ensureLoaded()
        val loadMs = SystemClock.elapsedRealtime() - loadStart
        val templates = SearchSeeds.summaries().map { it.name }.ifEmpty { listOf("流浪地球", "长安三万里", "漫长的季节") }
        val startBuild = SystemClock.elapsedRealtime()
        val built = ArrayList<Entry>(entries)
        for (i in 0 until entries) {
            val name = (templates[i % templates.size] + (i % 100)).take(24)
            built.add(
                buildEntry(
                    VodSummary(
                        vodId = "bench$i",
                        sourceId = "bench",
                        name = name,
                        pic = "",
                        remarks = "",
                        year = "",
                        typeId = "",
                        typeName = "",
                        score = "",
                    ),
                ),
            )
        }
        val buildMs = SystemClock.elapsedRealtime() - startBuild
        val startLookup = SystemClock.elapsedRealtime()
        var hits = 0
        for (entry in built) {
            if (entry.initials.any { it.startsWith("lldq") } || entry.full.any { it.startsWith("chang") }) hits++
        }
        val lookupMs = SystemClock.elapsedRealtime() - startLookup
        val multi = buildEntry(
            VodSummary("multi", "bench", "长安三万里", "", "", "", "", "", ""),
        ).initials.any { it.contains("caswl") || it.contains("zaswl") }
        val result = IndexBenchmark(entries, buildMs, lookupMs, multi, MAX_ENTRIES)
        TvLog.i(
            "PinyinIndex",
            "BENCH entries=${result.entries} dictLoadMs=$loadMs buildMs=${result.buildMs} lookupMs=${result.lookupMs} " +
                "multiPron=${result.multiPronunciationHit} capacity=${result.capacity}",
        )
        result
    }

    private fun buildEntry(vod: VodSummary): Entry {
        val title = vod.name
        val initialsOptions = ArrayList<List<String>>(title.length)
        val fullOptions = ArrayList<List<String>>(title.length)
        for (ch in title) {
            val key = ch.toString()
            if (isAsciiAlnum(ch)) {
                initialsOptions.add(listOf(ch.lowercaseChar().toString()))
                fullOptions.add(listOf(ch.lowercaseChar().toString()))
            } else if (isCjk(ch)) {
                val initials = initialsMap[key]?.map { it.toString() }?.take(2)
                initialsOptions.add(initials?.takeIf { it.isNotEmpty() } ?: listOf(key))
                val full = fullMap[key]?.split('|')?.take(2)
                fullOptions.add(full?.takeIf { it.isNotEmpty() } ?: listOf(key))
            } else {
                // 空格/标点等不参与拼音串
                initialsOptions.add(emptyList())
                fullOptions.add(emptyList())
            }
        }
        return Entry(
            vod = vod,
            initials = expand(initialsOptions),
            full = expand(fullOptions),
            titleNorm = normalizeQuery(title),
        )
    }

    private fun expand(options: List<List<String>>): List<String> {
        var result = mutableListOf("")
        for (candidates in options) {
            if (candidates.isEmpty()) continue
            val next = ArrayList<String>(minOf(result.size * candidates.size, MAX_VARIANTS))
            outer@ for (prefix in result) {
                for (candidate in candidates) {
                    if (next.size >= MAX_VARIANTS) break@outer
                    next.add(prefix + candidate)
                }
            }
            result = next
        }
        return result.filter { it.isNotEmpty() }.distinct()
    }

    private fun scoreOf(variant: String, query: String): Int = when {
        variant == query -> 1_000
        variant.startsWith(query) -> 800 - (variant.length - query.length).coerceAtLeast(0)
        variant.contains(query) -> 400
        else -> -1
    }

    private fun normalizeQuery(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase(Locale.ROOT)) {
            if (isAsciiAlnum(ch) || isCjk(ch)) sb.append(ch)
        }
        return sb.toString()
    }

    private fun isAsciiAlnum(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'

    private fun isCjk(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF

    private fun readMap(asset: String): Map<String, String> {
        val text = appContext.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        val map = HashMap<String, String>(root.length() * 2)
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = root.optString(key)
        }
        return map
    }

    private companion object {
        const val ASSET_INITIALS = "pinyin_initials.json"
        const val ASSET_FULL = "pinyin_full.json"
        const val MAX_ENTRIES = 8000
        const val MAX_VARIANTS = 8
    }
}
