package com.xingmu.tv.data

import android.content.Context
import com.xingmu.tv.core.AppDispatchers
import com.xingmu.tv.core.AppError
import com.xingmu.tv.core.ErrorCodes
import com.xingmu.tv.core.Result
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.source.SourceRepository
import kotlinx.coroutines.launch
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
    private val racer = EndpointRacer(appContext)
    private val client = MacCmsClient(cache, racer)
    private val builtin = BuiltinSources(appContext)
    private val states = SourceStateStore(appContext)
    private val overseas = OverseasMap(appContext)
    private val liveSeed = LiveSeed(appContext)

    /** t27：预置详情包（首启下载 + SQLite + 详情本地优先）。 */
    private val seedPrefs = DetailSeedPrefs(appContext)
    private val seedStore = DetailSeedStore(AndroidSeedDatabase(appContext))
    private val seedDetails = DetailSeedDetails(seedStore, seedPrefs::enabled)
    private val seedSync = DetailSeedSync.get(appContext)

    init {
        // t23：应用启动即后台竞速前几个源，用户点进详情前 winner 已就绪（不阻塞首帧）
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + AppDispatchers.Io,
        ).launch {
            runCatching {
                maccmsSources().take(4).forEach { source ->
                    racer.orderedEndpoints(source)
                }
            }
        }
        // t27：Wi-Fi 下后台拉预置详情包（378KB），不阻塞首帧；失败静默重试一次
        runCatching { seedSync.startIfNeeded() }
    }

    override suspend fun listSources(): Result<List<SourceConfig>> = Result.Ok(allSources())

    override suspend fun listAllSources(): Result<List<SourceConfig>> = Result.Ok(allSources())

    override suspend fun list(
        typeId: Int,
        page: Int,
        sort: SourceRepository.Sort,
        year: String?,
    ): Result<List<VodSummary>> {
        return when (val r = listPage(typeId, page, sort, year)) {
            is Result.Ok -> Result.Ok(r.value.items)
            is Result.Err -> r
        }
    }

    override suspend fun listPage(
        typeId: Int,
        page: Int,
        sort: SourceRepository.Sort,
        year: String?,
    ): Result<VodPage> {
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
            client.listPage(source, realTypeId, page, sort.toClientSort(), year)
        }
        if (groups.isEmpty()) {
            return Result.failure(ErrorCodes.SOURCE_OFFLINE, "所有源均请求失败，请检查网络或源状态")
        }
        val pages = groups.mapNotNull { it.second }
        return Result.Ok(
            VodPage(
                items = merge(pages.map { it.items }),
                total = pages.sumOf { it.total },
                pageCount = pages.maxOfOrNull { it.pageCount } ?: 0,
            ),
        )
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
        // t27：预置详情包命中 → 直接返回（"DetailPerf source=seed in=<n>ms"），7 天过期则后台静默刷新
        // SQLite 读必须离开主线程：UI 侧可能直接在 Main 调 getDetail
        val seedHit = withContext(AppDispatchers.Io) {
            runCatching { seedDetails.lookup(vodId, sourceId) }.getOrNull()
        }
        if (seedHit != null) {
            if (seedHit.stale) refreshSeedDetail(source, vodId)
            runCatching { com.xingmu.tv.player.VodPrefetch.prefetchDetail(appContext, seedHit.detail) }
            return Result.Ok(seedHit.detail)
        }
        val result = client.detail(source, vodId) { raw ->
            // 网络成功且包里已有该作品时覆盖本地行，避免预置数据越用越旧
            seedDetails.refreshFromNetwork(vodId, sourceId, source.name, "", raw)
        }
        if (result is Result.Ok) {
            runCatching { com.xingmu.tv.player.VodPrefetch.prefetchDetail(appContext, result.value) }
        }
        return result
    }

    /** 7 天过期后的静默刷新：不阻塞当前 300ms 首屏，失败静默。 */
    private fun refreshSeedDetail(source: SourceConfig, vodId: String) {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + AppDispatchers.Io,
        ).launch {
            runCatching {
                val started = System.currentTimeMillis()
                val refreshed = client.detail(source, vodId) { raw ->
                    seedDetails.refreshFromNetwork(vodId, source.id, source.name, "", raw)
                }
                if (refreshed is Result.Ok) {
                    TvLog.i(
                        "DetailPerf",
                        "source=seed refresh ok in=${System.currentTimeMillis() - started}ms vod=$vodId",
                    )
                }
            }
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
            if (section == null) {
                // t31：非规范分类（如子分类 喜剧片=22）直接当 MacCMS 原始 type_id 下传
                canonicalTypeId
            } else {
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
