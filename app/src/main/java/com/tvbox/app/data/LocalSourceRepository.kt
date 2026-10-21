package com.tvbox.app.data

import android.content.Context
import com.tvbox.app.core.AppDispatchers
import com.tvbox.app.core.AppError
import com.tvbox.app.core.ErrorCodes
import com.tvbox.app.core.Result
import com.tvbox.app.core.TvLog
import com.tvbox.app.source.SourceRepository
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 基于内置 MacCMS 源的默认源仓库。
 *
 * t10 的完整实现（导入/多级去重/健康度）就绪后通过 [AppServices.registerSources] 覆盖本实现；
 * 在那之前，本类保证 UI 有真实数据可用：多源聚合、端点降级、文件缓存、海外分类映射都已具备。
 */
class LocalSourceRepository(context: Context) : SourceRepository, SourceAdminDataSource {

    private val appContext = context.applicationContext
    private val cache = FileCache(File(appContext.cacheDir, "maccms"))
    private val client = MacCmsClient(cache)
    private val builtin = BuiltinSources(appContext)
    private val states = SourceStateStore(appContext)
    private val overseas = OverseasMap(appContext)
    private val liveSeed = LiveSeed(appContext)

    override suspend fun listSources(): Result<List<SourceConfig>> = Result.Ok(allSources())

    override suspend fun listAllSources(): Result<List<SourceConfig>> = Result.Ok(allSources())

    override suspend fun list(
        typeId: Int,
        page: Int,
        sort: SourceRepository.Sort,
    ): Result<List<VodSummary>> {
        val sources = maccmsSources()
        if (sources.isEmpty()) {
            return Result.failure(ErrorCodes.NO_SOURCE, "没有启用的点播源")
        }
        val overseasTypes = if (CanonicalCategory.isOverseas(typeId)) overseas.load() else emptyMap()
        val groups = aggregateAcrossSources(sources) { source ->
            val realTypeId = realTypeId(source, typeId, overseasTypes)
                ?: return@aggregateAcrossSources Result.failure(
                    ErrorCodes.NO_SOURCE,
                    "源《${source.name}》没有该分类",
                )
            client.list(source, realTypeId, page, sort.toClientSort())
        }
        if (groups.isEmpty()) {
            return Result.failure(ErrorCodes.SOURCE_OFFLINE, "所有源均请求失败，请检查网络或源状态")
        }
        return Result.Ok(merge(groups.map { it.second }))
    }

    override suspend fun search(keyword: String, page: Int): Result<List<VodSummary>> {
        val query = keyword.trim()
        if (query.isEmpty()) return Result.Ok(emptyList())
        val sources = maccmsSources()
        if (sources.isEmpty()) {
            return Result.failure(ErrorCodes.NO_SOURCE, "没有启用的点播源")
        }
        val groups = aggregateAcrossSources(sources) { source ->
            client.search(source, query, page)
        }
        if (groups.isEmpty()) {
            return Result.failure(ErrorCodes.SOURCE_OFFLINE, "搜索请求失败，请检查网络")
        }
        return Result.Ok(merge(groups.map { it.second }))
    }

    override suspend fun getDetail(vodId: String, sourceId: String): Result<VodDetail> {
        if (vodId.isBlank() || sourceId.isBlank()) {
            return Result.failure(ErrorCodes.SOURCE_INVALID, "缺少作品或源标识")
        }
        val source = allSources().firstOrNull { it.id == sourceId }
            ?: return Result.failure(ErrorCodes.SOURCE_OFFLINE, "源不存在或已被删除：$sourceId")
        return client.detail(source, vodId)
    }

    override suspend fun findAlternateSources(
        vodId: String,
        name: String,
        year: String,
    ): Result<List<VodSummary>> {
        if (name.isBlank()) return Result.Ok(emptyList())
        return when (val result = search(name, 1)) {
            is Result.Ok -> {
                val target = fingerprint(name, year)
                Result.Ok(
                    result.value.filter { candidate ->
                        fingerprint(candidate.name, candidate.year) == target &&
                            candidate.vodId != vodId
                    },
                )
            }
            is Result.Err -> result
        }
    }

    override suspend fun listChannels(): Result<List<Channel>> = try {
        Result.Ok(liveSeed.load())
    } catch (t: Throwable) {
        TvLog.e("LocalSourceRepository", "加载直播种子失败", t)
        Result.Err(AppError(ErrorCodes.IO, "直播种子读取失败：${t.message}", t))
    }

    override suspend fun setEnabled(sourceId: String, enabled: Boolean): Result<Unit> =
        withContext(AppDispatchers.Io) {
            states.setEnabled(sourceId, enabled)
            Result.Ok(Unit)
        }

    override suspend fun updatePriority(sourceId: String, priority: Int): Result<Unit> =
        withContext(AppDispatchers.Io) {
            states.setPriority(sourceId, priority)
            Result.Ok(Unit)
        }

    override suspend fun removeSource(sourceId: String): Result<Unit> =
        withContext(AppDispatchers.Io) {
            states.remove(sourceId)
            Result.Ok(Unit)
        }

    override suspend fun testSource(sourceId: String): Result<Int> {
        val source = allSources().firstOrNull { it.id == sourceId }
            ?: return Result.failure(ErrorCodes.SOURCE_OFFLINE, "源不存在：$sourceId")
        return when (val probe = client.probe(source)) {
            is Result.Ok -> Result.Ok(probe.value.toInt())
            is Result.Err -> probe
        }
    }

    // ---- 内部 ----

    private suspend fun allSources(): List<SourceConfig> = states.apply(builtin.load())

    private suspend fun maccmsSources(): List<SourceConfig> = allSources().filter {
        it.enabled && it.kind == SourceKind.MACCMS && it.baseUrl.isNotBlank()
    }

    private fun realTypeId(
        source: SourceConfig,
        canonicalTypeId: Int,
        overseasTypes: Map<String, OverseasMap.SourceTypes>,
    ): Int? = when (canonicalTypeId) {
        CanonicalCategory.MOVIE -> source.categories["MOVIE"]?.firstOrNull() ?: 1
        CanonicalCategory.TV -> source.categories["TV"]?.firstOrNull() ?: 2
        CanonicalCategory.ANIME -> source.categories["ANIME"]?.firstOrNull() ?: 4
        CanonicalCategory.VARIETY -> source.categories["VARIETY"]?.firstOrNull() ?: 3
        else -> {
            val section = CanonicalCategory.overseas.firstOrNull { it.id == canonicalTypeId }
                ?: return null
            overseas.typeIdFor(overseasTypes[source.id], section)
                ?: source.categories[section.categoryKey]?.firstOrNull()
                ?: when (section.categoryKey) {
                    "TV" -> 2
                    "ANIME" -> 4
                    "VARIETY" -> 3
                    else -> null
                }
        }
    }

    private fun merge(groups: List<List<VodSummary>>): List<VodSummary> {
        val merged = LinkedHashMap<String, VodSummary>()
        for (items in groups) {
            for (item in items) {
                val key = fingerprint(item.name, item.year)
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

    private fun fingerprint(name: String, year: String): String {
        val normalized = name
            .replace(NOISE_REGEX, "")
            .replace(SEPARATOR_REGEX, "")
            .lowercase(Locale.ROOT)
        return "$normalized|${year.trim()}"
    }

    private fun SourceRepository.Sort.toClientSort(): MacCmsClient.SourceSort =
        if (this == SourceRepository.Sort.HOT) MacCmsClient.SourceSort.HOT else MacCmsClient.SourceSort.LATEST

    private companion object {
        val NOISE_REGEX = Regex(
            "(第[一二三四五六七八九十0-9]+季|全[0-9]+集|全集|HDTV|HD|BD|4K|1080P|720P|国语|粤语|中字|中文字幕|未删减|高清)",
            RegexOption.IGNORE_CASE,
        )
        val SEPARATOR_REGEX = Regex("[\\s\\p{P}\\p{S}]+")
    }
}
