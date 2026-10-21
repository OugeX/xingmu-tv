package com.tvbox.app.data

import com.tvbox.app.core.Result
import com.tvbox.app.source.SourceRepository
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
    ): Result<List<VodSummary>> {
        val primaryResult = withTimeoutOrNull(PRIMARY_PROBE_MS) {
            primary.list(typeId, page, sort)
        }
        if (primaryResult is Result.Ok && primaryResult.value.isNotEmpty()) return primaryResult
        return fallback.list(typeId, page, sort)
    }

    override suspend fun search(keyword: String, page: Int): Result<List<VodSummary>> {
        val primaryResult = withTimeoutOrNull(PRIMARY_PROBE_MS) { primary.search(keyword, page) }
        if (primaryResult is Result.Ok && primaryResult.value.isNotEmpty()) return primaryResult
        return fallback.search(keyword, page)
    }

    override suspend fun getDetail(vodId: String, sourceId: String): Result<VodDetail> {
        val primaryResult = withTimeoutOrNull(PRIMARY_PROBE_MS) { primary.getDetail(vodId, sourceId) }
        if (primaryResult is Result.Ok) return primaryResult
        return fallback.getDetail(vodId, sourceId)
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
