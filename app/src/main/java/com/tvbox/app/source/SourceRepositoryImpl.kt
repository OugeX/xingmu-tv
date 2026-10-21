package com.tvbox.app.source

import android.content.Context
import com.tvbox.app.core.AppDispatchers
import com.tvbox.app.core.ErrorCodes
import com.tvbox.app.core.Result
import com.tvbox.app.core.TvLog
import com.tvbox.app.data.CanonicalCategory
import com.tvbox.app.data.Channel
import com.tvbox.app.data.LiveSeed
import com.tvbox.app.data.OverseasMap
import com.tvbox.app.data.SourceConfig
import com.tvbox.app.data.SourceAdminDataSource
import com.tvbox.app.data.SourceKind
import com.tvbox.app.data.VodDetail
import com.tvbox.app.data.VodSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 源仓库完整实现（t10）：SQLite 源表 + 内置源装载 + MacCMS 聚合 + m3u 频道。
 *
 * 线程模型：所有 suspend 函数内部切 [AppDispatchers.Io]/[AppDispatchers.IoLimited]，
 * UI 直接调用即可（core/AppScopes.kt 约定）。
 *
 * 注册方式：App 启动时调用 `SourceGraph.install(context)`，即可覆盖
 * `data/AppServices` 的默认 LocalSourceRepository（一行接线，见 docs/IMPORT_IMPL.md）。
 */
class SourceRepositoryImpl(context: Context) : SourceRepository, SourceAdminDataSource {

    private val appContext = context.applicationContext
    private val store = SourceStore(appContext)
    private val liveSeed = LiveSeed(appContext)
    private val overseas = OverseasMap(appContext)

    @Volatile
    private var seedRequested = false

    private suspend fun ensureSeeded() {
        if (seedRequested) return
        synchronized(this) {
            if (seedRequested) return
            seedRequested = true
        }
        store.seedBuiltin()
    }

    // ---------------------------------------------------------------- 源管理

    override suspend fun listSources(): Result<List<SourceConfig>> = withContext(AppDispatchers.Io) {
        ensureSeeded()
        Result.Ok(allStored().filter { it.enabled }.map { it.toSourceConfig() })
    }

    /** 源管理页用：包含停用的源（按 priority 升序）。 */
    override suspend fun listAllSources(): Result<List<SourceConfig>> = withContext(AppDispatchers.Io) {
        ensureSeeded()
        Result.Ok(allStored().map { it.toSourceConfig() })
    }

    /** 源管理页用：带探测状态的完整快照。 */
    suspend fun listSourceStates(): Result<List<StoredSource>> = withContext(AppDispatchers.Io) {
        ensureSeeded()
        Result.Ok(allStored())
    }

    suspend fun findSource(sourceId: String): Result<SourceConfig> = withContext(AppDispatchers.Io) {
        ensureSeeded()
        store.byId(sourceId)?.let { Result.Ok(it.toSourceConfig()) }
            ?: Result.failure(ErrorCodes.SOURCE_OFFLINE, "源不存在：$sourceId")
    }

    /** 导入成功后写入（幂等：按 id 更新 / 端点指纹复用旧 id）。 */
    suspend fun addSources(parsed: List<ParsedSource>, userTouched: Boolean = true): SourceStore.UpsertOutcome =
        withContext(AppDispatchers.Io) {
            ensureSeeded()
            store.upsert(parsed, userTouched)
        }

    /** 导入后回写探测结果（L3/L4）。 */
    suspend fun recordProbe(sourceId: String, outcome: ProbeOutcome) = withContext(AppDispatchers.Io) {
        val existing = store.byId(sourceId) ?: return@withContext
        store.updateProbe(
            id = sourceId,
            state = if (outcome.ok) "OK" else outcome.state.name,
            failStreak = if (outcome.ok) 0 else existing.failStreak + 1,
            activeBaseUrl = null,
        )
    }

    override suspend fun setEnabled(sourceId: String, enabled: Boolean): Result<Unit> =
        withContext(AppDispatchers.Io) {
            ensureSeeded()
            if (store.setEnabled(sourceId, enabled)) Result.Ok(Unit)
            else Result.failure(ErrorCodes.SOURCE_OFFLINE, "源不存在：$sourceId")
        }

    override suspend fun updatePriority(sourceId: String, priority: Int): Result<Unit> =
        withContext(AppDispatchers.Io) {
            if (priority !in 0..1000) {
                return@withContext Result.failure(ErrorCodes.SOURCE_INVALID, "优先级必须在 0..1000")
            }
            ensureSeeded()
            if (store.setPriority(sourceId, priority)) Result.Ok(Unit)
            else Result.failure(ErrorCodes.SOURCE_OFFLINE, "源不存在：$sourceId")
        }

    override suspend fun removeSource(sourceId: String): Result<Unit> = withContext(AppDispatchers.Io) {
        ensureSeeded()
        if (store.remove(sourceId)) {
            SourceProbe.clearCache()
            Result.Ok(Unit)
        } else {
            Result.failure(ErrorCodes.SOURCE_OFFLINE, "源不存在：$sourceId")
        }
    }

    override suspend fun testSource(sourceId: String): Result<Int> = withContext(AppDispatchers.Io) {
        ensureSeeded()
        val stored = store.byId(sourceId)
            ?: return@withContext Result.failure(ErrorCodes.SOURCE_OFFLINE, "源不存在：$sourceId")
        val parsed = stored.toParsedSource()
            ?: return@withContext Result.failure(ErrorCodes.SOURCE_INVALID, "源配置已损坏：$sourceId")
        val (l3, l4) = SourceProbe.probeFull(parsed)
        val final = l4 ?: l3
        store.updateProbe(
            id = sourceId,
            state = if (final.ok) "OK" else final.state.name,
            failStreak = if (final.ok) 0 else stored.failStreak + 1,
            activeBaseUrl = parsed.baseUrl.ifEmpty { null },
        )
        if (!final.ok) {
            return@withContext Result.failure(
                ErrorCodes.SOURCE_OFFLINE,
                "源不可用：${final.message ?: final.state.name}",
            )
        }
        Result.Ok((l3.latencyMs ?: 0L).toInt())
    }

    // ---------------------------------------------------------------- 点播聚合

    override suspend fun list(
        typeId: Int,
        page: Int,
        sort: SourceRepository.Sort,
    ): Result<List<VodSummary>> = withContext(AppDispatchers.Io) {
        ensureSeeded()
        val sources = maccmsSources()
        if (sources.isEmpty()) return@withContext Result.failure(ErrorCodes.NO_SOURCE, "没有启用的点播源")
        val overseasMap = if (CanonicalCategory.isOverseas(typeId)) overseas.load() else emptyMap()
        val groups = aggregate(sources) { src ->
            val real = realTypeId(src, typeId, overseasMap)
            if (real == null) {
                Result.failure(ErrorCodes.NO_SOURCE, "源《${src.name}》没有该分类")
            } else {
                MacCmsClient.fetchList(src, real, page, sort)
            }
        }.mapNotNull { it.second?.items }
        if (groups.isEmpty()) {
            return@withContext Result.failure(ErrorCodes.SOURCE_OFFLINE, "所有源均请求失败，请检查网络或源状态")
        }
        Result.Ok(mergeSummaries(groups))
    }

    override suspend fun search(keyword: String, page: Int): Result<List<VodSummary>> =
        withContext(AppDispatchers.Io) {
            ensureSeeded()
            val query = keyword.trim()
            if (query.isEmpty()) return@withContext Result.Ok(emptyList())
            val sources = maccmsSources()
            if (sources.isEmpty()) return@withContext Result.failure(ErrorCodes.NO_SOURCE, "没有启用的点播源")
            val groups = aggregate(sources) { src ->
                MacCmsClient.search(src, query, page)
            }.mapNotNull { it.second?.items }
            if (groups.isEmpty()) {
                return@withContext Result.failure(ErrorCodes.SOURCE_OFFLINE, "搜索请求失败，请检查网络")
            }
            Result.Ok(mergeSummaries(groups))
        }

    override suspend fun getDetail(vodId: String, sourceId: String): Result<VodDetail> =
        withContext(AppDispatchers.Io) {
            ensureSeeded()
            if (vodId.isBlank() || sourceId.isBlank()) {
                return@withContext Result.failure(ErrorCodes.SOURCE_INVALID, "缺少作品或源标识")
            }
            val parsed = store.byId(sourceId)?.toParsedSource()
                ?: return@withContext Result.failure(ErrorCodes.SOURCE_OFFLINE, "源不存在或已删除：$sourceId")
            if (parsed.kind != SourceKind.MACCMS) {
                return@withContext Result.failure(ErrorCodes.SOURCE_INVALID, "该源不支持点播详情")
            }
            when (val r = MacCmsClient.fetchDetail(parsed, vodId)) {
                is Result.Ok -> {
                    // 补上线路名（Episode.playFrom 由 UI 显示“线路”）
                    val fixed = r.value.episodesByFrom.mapValues { (from, eps) ->
                        eps.map { it.copy(playFrom = from) }
                    }
                    Result.Ok(r.value.copy(episodesByFrom = fixed))
                }
                is Result.Err -> r
            }
        }

    override suspend fun findAlternateSources(
        vodId: String,
        name: String,
        year: String,
    ): Result<List<VodSummary>> {
        if (name.isBlank()) return Result.Ok(emptyList())
        return when (val result = search(name, 1)) {
            is Result.Ok -> {
                val key = workKey(name, year)
                Result.Ok(
                    result.value.filter {
                        workKey(it.name, it.year) == key && !(it.vodId == vodId && it.sourceCount <= 1)
                    },
                )
            }
            is Result.Err -> result
        }
    }

    override suspend fun listChannels(): Result<List<Channel>> = withContext(AppDispatchers.Io) {
        ensureSeeded()
        val merged = LinkedHashMap<String, Channel>()
        val seed = runCatching { liveSeed.load() }.getOrElse { emptyList() }
        for (ch in seed) merged[channelKey(ch)] = ch
        for (stored in allStored()) {
            if (!stored.enabled || stored.kind != "m3u") continue
            val parsed = stored.toParsedSource() ?: continue
            for (ch in SourceProbe.fetchM3uChannels(parsed)) {
                val key = channelKey(ch)
                val existing = merged[key]
                merged[key] = if (existing == null) {
                    ch
                } else {
                    existing.copy(urls = (existing.urls + ch.urls).distinctBy { it.url })
                }
            }
        }
        Result.Ok(merged.values.toList())
    }

    // ---------------------------------------------------------------- 内部

    private fun allStored(): List<StoredSource> = store.all()

    private fun StoredSource.toSourceConfig(): SourceConfig =
        toParsedSource()?.toSourceConfig()
            ?: SourceConfig(id = id, name = name, kind = kind.toSourceKindOrNull() ?: SourceKind.MACCMS,
                baseUrl = "", priority = priority, enabled = enabled)

    private fun maccmsSources(): List<ParsedSource> = store.all()
        .asSequence()
        .filter { it.enabled && it.kind == "maccms" }
        .mapNotNull { it.toParsedSource() }
        .filter { it.baseUrl.isNotBlank() }
        .toList()

    private fun realTypeId(
        src: ParsedSource,
        canonicalTypeId: Int,
        overseasTypes: Map<String, OverseasMap.SourceTypes>,
    ): Int? = when (canonicalTypeId) {
        CanonicalCategory.MOVIE -> src.categories["MOVIE"]?.firstOrNull() ?: 1
        CanonicalCategory.TV -> src.categories["TV"]?.firstOrNull() ?: 2
        CanonicalCategory.ANIME -> src.categories["ANIME"]?.firstOrNull() ?: 4
        CanonicalCategory.VARIETY -> src.categories["VARIETY"]?.firstOrNull() ?: 3
        else -> {
            val section = CanonicalCategory.overseas.firstOrNull { it.id == canonicalTypeId }
            if (section == null) {
                // 非规范分类：当作 MacCMS 原始 type_id 直接下传
                canonicalTypeId
            } else {
                overseas.typeIdFor(overseasTypes[src.id], section)
                    ?: src.categories[section.categoryKey]?.firstOrNull()
                    ?: when (section.categoryKey) {
                        "TV" -> 2
                        "ANIME" -> 4
                        "VARIETY" -> 3
                        else -> null
                    }
            }
        }
    }

    /**
     * 多源并发聚合：全局并发 3，单源 3.5s，硬截止 4.5s（PLAN_SOURCES §7.2）。
     * 单源失败不影响整体（返回 Err 条目被过滤）。
     */
    private suspend fun <T> aggregate(
        sources: List<ParsedSource>,
        block: suspend (ParsedSource) -> Result<T>,
    ): List<Pair<ParsedSource, T>> = coroutineScope {
        val semaphore = Semaphore(3)
        val jobs = sources.map { src ->
            async {
                semaphore.withPermit {
                    withTimeoutOrNull(PER_SOURCE_MS) { block(src) }
                }
            }
        }
        val awaited = withTimeoutOrNull(HARD_DEADLINE_MS) { jobs.awaitAll() } ?: emptyList()
        awaited.mapIndexedNotNull { index, result ->
            if (result is Result.Ok) sources.getOrNull(index)?.let { it to result.value } else null
        }
    }

    private fun mergeSummaries(groups: List<List<VodSummary>>): List<VodSummary> {
        val merged = LinkedHashMap<String, VodSummary>()
        for (items in groups) {
            for (item in items) {
                val key = workKey(item.name, item.year)
                val previous = merged[key]
                merged[key] = if (previous == null) {
                    item
                } else {
                    previous.copy(
                        sourceCount = previous.sourceCount + 1,
                        score = maxScore(previous.score, item.score),
                        remarks = if (item.remarks.length > previous.remarks.length) item.remarks else previous.remarks,
                    )
                }
            }
        }
        return merged.values.toList()
    }

    private fun maxScore(a: String, b: String): String {
        val na = a.toFloatOrNull() ?: 0f
        val nb = b.toFloatOrNull() ?: 0f
        return if (nb > na) b else a
    }

    private fun workKey(name: String, year: String): String {
        val normalized = name.replace(NOISE_REGEX, "").replace(SEPARATOR_REGEX, "").lowercase(Locale.ROOT)
        return "$normalized|${year.trim()}"
    }

    private fun channelKey(ch: Channel): String =
        ch.tvgId?.lowercase() ?: (ch.group + "|" + ch.name.replace(Regex("\\s+"), "").lowercase())

    fun close() {
        store.close()
    }

    private companion object {
        const val TAG = "SourceRepo"
        const val PER_SOURCE_MS = 3500L
        const val HARD_DEADLINE_MS = 4500L
        val NOISE_REGEX = Regex(
            "(第[一二三四五六七八九十0-9]+季|全[0-9]+集|全集|HDTV|HD|BD|4K|1080P|720P|国语|粤语|中字|中文字幕|未删减|高清)",
            RegexOption.IGNORE_CASE,
        )
        val SEPARATOR_REGEX = Regex("[\\s\\p{P}\\p{S}]+")
    }
}
