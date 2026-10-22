package com.xingmu.tv.data

import android.content.Context
import com.xingmu.tv.core.AppDispatchers
import com.xingmu.tv.core.ErrorCodes
import com.xingmu.tv.core.Result
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.source.SourceRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.Locale

/**
 * 首页「真实热度」模型与排序（t32）。
 *
 * 热度信号（与任务书一致）：
 *   1. `sourceCount`  —— 同一作品在几个源出现（最可靠，跨源重合度）
 *   2. `hits`         —— `vod_hits`（bfzy1 恒 0；保留比较以满足契约，不会改变真实顺序）
 *   3. `score`        —— 镜像站评分（假分用 [VodRanking.effectiveScore] 归一，权重靠后）
 *   4. `updatedAtMs`  —— `vod_time_add`，只做 tie-break，避免首页全是老片
 *
 * 注意：这是**首页热度**口径；分类页/「排序」仍用 t31 的 [VodRanking.rankHot]（captain 实测校正）。
 * 纯函数，单测见 `HomeHeatTest`。
 */
object HomeHeat {

    private val NOISE = Regex(
        "(第[一二三四五六七八九十0-9]+季|全[0-9]+集|全集|HDTV|HD|BD|4K|1080P|720P|国语|粤语|中字|中文字幕|未删减|高清)",
        RegexOption.IGNORE_CASE,
    )
    private val SEPARATOR = Regex("[\\s\\p{P}\\p{S}]+")

    /** 跨源去重键：归一化标题 + 年份。 */
    fun key(name: String, year: String): String =
        name.replace(NOISE, "").replace(SEPARATOR, "").lowercase(Locale.ROOT) + "|" + year.trim()

    /** 评分（复用 t31 的假分校正：<=1.0 视为镜像站默认假分）。 */
    fun scoreOf(score: String): Double = VodRanking.effectiveScore(score)

    /** 热度排序：sourceCount → hits → score → time；完全相同时保持输入顺序（稳定排序）。 */
    fun rank(items: List<VodSummary>): List<VodSummary> = items.sortedWith(
        compareByDescending<VodSummary> { it.sourceCount }
            .thenByDescending { it.hits }
            .thenByDescending { scoreOf(it.score) }
            .thenByDescending { it.updatedAtMs },
    )

    /** hero 选择：候选池为空时回退到现有第一条逻辑（调用方传入兜底）。 */
    fun heroFrom(rankedPool: List<VodSummary>, fallback: VodSummary?): VodSummary? =
        rankedPool.firstOrNull() ?: fallback

    /** 多源同作品合并：保留更完整的字段与更高的热度信号。 */
    fun merge(a: VodSummary, b: VodSummary): VodSummary = a.copy(
        pic = a.pic.ifBlank { b.pic },
        picThumb = a.picThumb.ifBlank { b.picThumb },
        remarks = if (b.remarks.length > a.remarks.length) b.remarks else a.remarks,
        score = if (scoreOf(b.score) > scoreOf(a.score)) b.score else a.score,
        hits = maxOf(a.hits, b.hits),
        updatedAtMs = maxOf(a.updatedAtMs, b.updatedAtMs),
        area = a.area.ifBlank { b.area },
        typeName = a.typeName.ifBlank { b.typeName },
    )
}

/** 首页一行（title + 规范分类 id + 卡片）。[typeId] 为 0 表示没有「更多」入口（如「大家都在看」）。 */
@androidx.compose.runtime.Immutable
data class HomeFeedRow(
    val title: String,
    val typeId: Int,
    val items: List<VodSummary>,
)

/** 首页热度结果（可整包落缓存）。 */
@androidx.compose.runtime.Immutable
data class HomeFeed(
    val hero: VodSummary?,
    val rows: List<HomeFeedRow>,
    val poolSize: Int = 0,
    val generatedAtMs: Long = 0L,
    val fromCache: Boolean = false,
)

/**
 * 首页候选池 + 热度聚合 + 30 分钟文件缓存（t32）。
 *
 * 候选池：type 20/30/39/45 × page 1–2 × priority 前 3 个可用 MacCMS 源，
 * 全局并发 3、单请求 3.5s、硬截止 12s（到点用已完成的部分，不阻塞 UI）。
 * 所有网络/解析都在 [AppDispatchers] 的 IO 侧执行；调用方只在后台调用 [refresh]。
 */
class HomeFeedRepository(context: Context) {

    private val appContext = context.applicationContext
    private val cache = FileCache(File(appContext.cacheDir, "homefeed"))
    private val client = MacCmsClient(FileCache(File(appContext.cacheDir, "homefeed_net")))

    private data class Bucket(val sourceId: String, val canonical: Int, val items: List<VodSummary>)

    private data class FetchTask(
        val source: SourceConfig,
        val canonical: Int,
        val realType: Int,
        val page: Int,
    )

    private class Entry(var vod: VodSummary) {
        val sources = LinkedHashSet<String>()
        val canonical = LinkedHashSet<Int>()
    }

    /** 冷启动：先读 30 分钟内的缓存立即渲染 hero；返回 null 表示无可用缓存。 */
    suspend fun cached(): HomeFeed? = withContext(AppDispatchers.Io) {
        val entry = cache.read(CACHE_KEY) ?: return@withContext null
        if (!cache.isFresh(entry, CACHE_TTL_MS)) return@withContext null
        val feed = try {
            decode(entry.data)
        } catch (t: Throwable) {
            TvLog.w(TAG, "首页缓存解析失败", t)
            null
        }
        feed?.copy(fromCache = true)
    }

    /** 后台刷新候选池并写缓存；失败返回 Err（调用方保留现有/缓存 UI）。 */
    suspend fun refresh(repo: SourceRepository): Result<HomeFeed> = withContext(AppDispatchers.Io) {
        coroutineScope {
        val sourceResult = repo.listSources()
        val sources = if (sourceResult is Result.Ok) {
            sourceResult.value
                .filter { it.kind == SourceKind.MACCMS && it.enabled && it.baseUrl.isNotBlank() }
                .sortedBy { it.priority }
                .take(MAX_SOURCES)
        } else {
            emptyList()
        }
        if (sources.isEmpty()) {
            return@coroutineScope Result.failure(ErrorCodes.NO_SOURCE, "没有启用的点播源")
        }

        val tasks = ArrayList<FetchTask>()
        for (source in sources) {
            for (canonical in CANONICAL_TYPES) {
                val realType = realType(source, canonical) ?: continue
                for (page in 1..PAGES_PER_TYPE) {
                    tasks.add(FetchTask(source, canonical, realType, page))
                }
            }
        }

        val buckets = Collections.synchronizedList(ArrayList<Bucket>())
        val semaphore = Semaphore(MAX_CONCURRENCY)
        val jobs = tasks.map { task ->
            async {
                semaphore.withPermit {
                    val result = withTimeoutOrNull(PER_REQUEST_MS) {
                        client.list(task.source, task.realType, task.page, MacCmsClient.SourceSort.LATEST)
                    }
                    if (result is Result.Ok && result.value.isNotEmpty()) {
                        buckets.add(Bucket(task.source.id, task.canonical, result.value))
                    }
                }
            }
        }
        // 硬截止：到点取消未完成请求，用已完成的部分构建（部分候选也比没有强）
        withTimeoutOrNull(HARD_DEADLINE_MS) { jobs.joinAll() }
        jobs.forEach { it.cancel() }

        val feed = buildFeed(synchronized(buckets) { buckets.toList() })
            ?: return@coroutineScope Result.failure(ErrorCodes.SOURCE_OFFLINE, "热度候选池为空")
        withContext(AppDispatchers.IoLimited) {
            runCatching { cache.write(CACHE_KEY, encode(feed)) }
                .onFailure { TvLog.w(TAG, "首页缓存写入失败", it) }
        }
        Result.Ok(feed)
        }
    }

    // ---------------------------------------------------------------- 聚合

    private fun buildFeed(buckets: List<Bucket>): HomeFeed? {
        if (buckets.isEmpty()) return null
        val byKey = LinkedHashMap<String, Entry>()
        for (bucket in buckets) {
            for (item in bucket.items) {
                if (item.vodId.isBlank() || item.name.isBlank()) continue
                val key = HomeHeat.key(item.name, item.year)
                val entry = byKey[key]
                if (entry == null) {
                    byKey[key] = Entry(item).apply {
                        sources.add(bucket.sourceId)
                        canonical.add(bucket.canonical)
                    }
                } else {
                    entry.sources.add(bucket.sourceId)
                    entry.canonical.add(bucket.canonical)
                    entry.vod = HomeHeat.merge(entry.vod, item)
                }
            }
        }
        if (byKey.isEmpty()) return null

        val all = HomeHeat.rank(byKey.values.map { it.vod.copy(sourceCount = it.sources.size) })
        val hero = HomeHeat.heroFrom(all, null)
        val rows = ArrayList<HomeFeedRow>()

        val everyone = all.filter { it.sourceCount >= 2 }.take(TOP_N)
        if (everyone.isNotEmpty()) {
            rows.add(HomeFeedRow("大家都在看 · 跨源热播", 0, everyone))
        }
        for ((canonical, title) in ROW_TITLES) {
            val entries = byKey.values.filter { canonical in it.canonical }
            if (entries.isEmpty()) continue
            val ranked = HomeHeat.rank(entries.map { it.vod.copy(sourceCount = it.sources.size) }).take(TOP_N)
            if (ranked.isNotEmpty()) rows.add(HomeFeedRow(title, canonical, ranked))
        }
        if (rows.isEmpty()) {
            rows.add(HomeFeedRow("热门推荐", CanonicalCategory.MOVIE, all.take(TOP_N)))
        }
        return HomeFeed(
            hero = hero,
            rows = rows,
            poolSize = all.size,
            generatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun realType(source: SourceConfig, canonical: Int): Int? {
        val key = when (canonical) {
            CanonicalCategory.MOVIE -> "MOVIE"
            CanonicalCategory.TV -> "TV"
            CanonicalCategory.ANIME -> "ANIME"
            CanonicalCategory.VARIETY -> "VARIETY"
            else -> return null
        }
        return source.categories[key]?.firstOrNull() ?: when (canonical) {
            CanonicalCategory.MOVIE -> 1
            CanonicalCategory.TV -> 2
            CanonicalCategory.ANIME -> 4
            CanonicalCategory.VARIETY -> 3
            else -> null
        }
    }

    // ---------------------------------------------------------------- 缓存序列化

    private fun encode(feed: HomeFeed): String {
        val root = JSONObject()
        root.put("v", CACHE_VERSION)
        root.put("generatedAt", feed.generatedAtMs)
        root.put("pool", feed.poolSize)
        root.put("hero", feed.hero?.let { summaryToJson(it) })
        root.put(
            "rows",
            JSONArray().apply {
                for (row in feed.rows) {
                    put(
                        JSONObject().apply {
                            put("title", row.title)
                            put("typeId", row.typeId)
                            put("items", JSONArray().apply { row.items.forEach { put(summaryToJson(it)) } })
                        },
                    )
                }
            },
        )
        return root.toString()
    }

    private fun decode(text: String): HomeFeed? {
        val root = JSONObject(text)
        if (root.optInt("v", 0) != CACHE_VERSION) return null
        val hero = root.optJSONObject("hero")?.let(::jsonToSummary)
        val rowsArray = root.optJSONArray("rows") ?: JSONArray()
        val rows = ArrayList<HomeFeedRow>(rowsArray.length())
        for (i in 0 until rowsArray.length()) {
            val row = rowsArray.optJSONObject(i) ?: continue
            val itemsArray = row.optJSONArray("items") ?: continue
            val items = ArrayList<VodSummary>(itemsArray.length())
            for (j in 0 until itemsArray.length()) {
                itemsArray.optJSONObject(j)?.let { items.add(jsonToSummary(it)) }
            }
            if (items.isNotEmpty()) {
                rows.add(HomeFeedRow(row.optString("title"), row.optInt("typeId"), items))
            }
        }
        if (rows.isEmpty() && hero == null) return null
        return HomeFeed(
            hero = hero,
            rows = rows,
            poolSize = root.optInt("pool", 0),
            generatedAtMs = root.optLong("generatedAt", 0L),
        )
    }

    private fun summaryToJson(item: VodSummary): JSONObject = JSONObject().apply {
        put("vodId", item.vodId)
        put("sourceId", item.sourceId)
        put("name", item.name)
        put("pic", item.pic)
        put("picThumb", item.picThumb)
        put("remarks", item.remarks)
        put("year", item.year)
        put("typeId", item.typeId)
        put("typeName", item.typeName)
        put("score", item.score)
        put("area", item.area)
        put("sourceCount", item.sourceCount)
        put("hits", item.hits)
        put("updatedAtMs", item.updatedAtMs)
    }

    private fun jsonToSummary(o: JSONObject): VodSummary = VodSummary(
        vodId = o.optString("vodId"),
        sourceId = o.optString("sourceId"),
        name = o.optString("name"),
        pic = o.optString("pic"),
        remarks = o.optString("remarks"),
        year = o.optString("year"),
        typeId = o.optString("typeId"),
        typeName = o.optString("typeName"),
        score = o.optString("score"),
        sourceCount = o.optInt("sourceCount", 1),
        picThumb = o.optString("picThumb"),
        area = o.optString("area"),
        updatedAtMs = o.optLong("updatedAtMs", 0L),
        hits = o.optLong("hits", 0L),
    )

    private companion object {
        const val TAG = "HomeFeed"
        const val CACHE_KEY = "homefeed.v1"
        const val CACHE_VERSION = 1
        /** 热度缓存 TTL：30 分钟（过期冷启动走兜底 + 后台刷新）。 */
        const val CACHE_TTL_MS = 30 * 60 * 1000L
        const val MAX_SOURCES = 3
        const val PAGES_PER_TYPE = 2
        const val MAX_CONCURRENCY = 3
        const val PER_REQUEST_MS = 3_500L
        const val HARD_DEADLINE_MS = 12_000L
        const val TOP_N = 8

        val CANONICAL_TYPES = listOf(
            CanonicalCategory.MOVIE,
            CanonicalCategory.TV,
            CanonicalCategory.ANIME,
            CanonicalCategory.VARIETY,
        )

        /** 行标题（顺序即首页显示顺序；「大家都在看」单独插在最前）。 */
        val ROW_TITLES = listOf(
            CanonicalCategory.MOVIE to "热门电影",
            CanonicalCategory.TV to "最新剧集",
            CanonicalCategory.ANIME to "动漫热播",
            CanonicalCategory.VARIETY to "综艺更新",
        )
    }
}
