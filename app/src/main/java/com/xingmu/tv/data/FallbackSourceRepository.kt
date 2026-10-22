package com.xingmu.tv.data

import com.xingmu.tv.core.Result
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.source.SourceRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 读路径容错：优先 t10 的完整实现（SQLite + 导入/探测），
 * 若它在当前网络下超时/返回空（真机实测：7 个源在 3.5s 内全部超时的场景），
 * 立即回退到内置 [LocalSourceRepository]（同样的 MacCMS 解析，超时更宽松、带文件缓存）。
 *
 * 写操作（启停/优先级/删除/测速）始终走 primary，保证导入/源状态一致。
 */
class FallbackSourceRepository(
    private val primary: SourceRepository,
    private val fallback: LocalSourceRepository,
) : SourceRepository, SourceAdminDataSource {

    override suspend fun listSources(): Result<List<SourceConfig>> {
        val primaryResult = withTimeoutOrNull(PRIMARY_PROBE_MS) { primary.listSources() }
        return if (primaryResult is Result.Ok && primaryResult.value.isNotEmpty()) {
            primaryResult
        } else {
            fallback.listSources()
        }
    }

    override suspend fun list(
        typeId: Int,
        page: Int,
        sort: SourceRepository.Sort,
        year: String?,
    ): Result<List<VodSummary>> {
        val primaryResult = withTimeoutOrNull(PRIMARY_PROBE_MS) {
            primary.list(typeId, page, sort, year)
        }
        if (primaryResult is Result.Ok && primaryResult.value.isNotEmpty()) return primaryResult
        return fallback.list(typeId, page, sort, year)
    }

    /** t31：优先 primary 的 total；primary 不可用时用 fallback 的 total。 */
    override suspend fun listPage(
        typeId: Int,
        page: Int,
        sort: SourceRepository.Sort,
        year: String?,
    ): Result<VodPage> {
        val primaryResult = withTimeoutOrNull(PRIMARY_PROBE_MS) {
            primary.listPage(typeId, page, sort, year)
        }
        if (primaryResult is Result.Ok && primaryResult.value.items.isNotEmpty()) return primaryResult
        return fallback.listPage(typeId, page, sort, year)
    }

    override suspend fun search(keyword: String, page: Int): Result<List<VodSummary>> {
        val primaryResult = withTimeoutOrNull(PRIMARY_PROBE_MS) { primary.search(keyword, page) }
        if (primaryResult is Result.Ok && primaryResult.value.isNotEmpty()) return primaryResult
        return fallback.search(keyword, page)
    }

    override suspend fun getDetail(vodId: String, sourceId: String): Result<VodDetail> {
        // t23：详情优先走 data 层（端点竞速 + ids + 6h 文件缓存 + 8 条内存缓存），
        // t10 primary 仅作为兜底，避免 macapi1 在盒子上 17s 慢请求挡住详情页。
        val started = System.currentTimeMillis()
        val fallbackResult = fallback.getDetail(vodId, sourceId)
        if (fallbackResult is Result.Ok) {
            TvLog.i("DetailPerf", "fallback ok in=${System.currentTimeMillis() - started}ms vod=$vodId")
            return fallbackResult
        }
        val primaryResult = withTimeoutOrNull(1_500L) { primary.getDetail(vodId, sourceId) }
        if (primaryResult is Result.Ok) {
            TvLog.i("DetailPerf", "primary ok in=${System.currentTimeMillis() - started}ms vod=$vodId")
            return primaryResult
        }
        return fallbackResult
    }

    override suspend fun findAlternateSources(
        vodId: String,
        name: String,
        year: String,
    ): Result<List<VodSummary>> {
        val primaryResult = withTimeoutOrNull(PRIMARY_PROBE_MS) {
            primary.findAlternateSources(vodId, name, year)
        }
        if (primaryResult is Result.Ok && primaryResult.value.isNotEmpty()) return primaryResult
        return fallback.findAlternateSources(vodId, name, year)
    }

    override suspend fun listChannels(): Result<List<Channel>> {
        val primaryResult = withTimeoutOrNull(PRIMARY_PROBE_MS) { primary.listChannels() }
        if (primaryResult is Result.Ok && primaryResult.value.isNotEmpty()) return primaryResult
        return fallback.listChannels()
    }

    override suspend fun setEnabled(sourceId: String, enabled: Boolean): Result<Unit> =
        primary.setEnabled(sourceId, enabled)

    override suspend fun updatePriority(sourceId: String, priority: Int): Result<Unit> =
        primary.updatePriority(sourceId, priority)

    override suspend fun removeSource(sourceId: String): Result<Unit> = primary.removeSource(sourceId)

    override suspend fun testSource(sourceId: String): Result<Int> = primary.testSource(sourceId)

    override suspend fun listAllSources(): Result<List<SourceConfig>> {
        val primaryResult = withTimeoutOrNull(PRIMARY_PROBE_MS) {
            (primary as? SourceAdminDataSource)?.listAllSources()
        }
        if (primaryResult is Result.Ok && primaryResult.value.isNotEmpty()) return primaryResult
        return fallback.listAllSources()
    }

    private companion object {
        /** 主实现快速失败，给 fallback 留出时间，避免首页等满 4.5s×N。 */
        const val PRIMARY_PROBE_MS = 2_800L
    }
}
