package com.tvbox.app.data

import com.tvbox.app.core.AppError
import com.tvbox.app.core.ErrorCodes
import com.tvbox.app.core.Result
import com.tvbox.app.core.TvLog
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.URLEncoder

/**
 * MacCMS 站点客户端：负责 URL 组装、多端点降级、文件缓存与 JSON 解析。
 *
 * 端点必须带结尾斜杠（不带会被 307 到 http，见 docs/TOOLCHAIN.md）；
 * 多端点顺序：baseUrl → fallbackBaseUrls。
 */
class MacCmsClient(private val cache: FileCache) {

    suspend fun list(
        source: SourceConfig,
        typeId: Int,
        page: Int,
        sort: SourceSort,
    ): Result<List<VodSummary>> {
        val order = if (sort == SourceSort.HOT) "hits" else "time"
        val query = "?ac=detail&t=$typeId&pg=${page.coerceAtLeast(1)}&by=$order"
        return when (val raw = fetch(source, "list|${source.id}|$typeId|$page|$order", query, LIST_TTL_MS)) {
            is Result.Ok -> MacCmsParser.parseList(raw.value, source.id)
            is Result.Err -> raw
        }
    }

    suspend fun search(source: SourceConfig, keyword: String, page: Int): Result<List<VodSummary>> {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val query = "?ac=detail&wd=$encoded&pg=${page.coerceAtLeast(1)}"
        return when (val raw = fetch(source, "search|${source.id}|$encoded|$page", query, LIST_TTL_MS)) {
            is Result.Ok -> MacCmsParser.parseList(raw.value, source.id)
            is Result.Err -> raw
        }
    }

    suspend fun detail(source: SourceConfig, vodId: String): Result<VodDetail> {
        val query = "?ac=detail&ids=${URLEncoder.encode(vodId, "UTF-8")}&pg=1"
        return when (val raw = fetch(source, "detail|${source.id}|$vodId", query, DETAIL_TTL_MS)) {
            is Result.Ok -> MacCmsParser.parseDetail(raw.value, source.id, vodId)
            is Result.Err -> raw
        }
    }

    /** 源连通性测试：返回首字节到完整响应的耗时（ms）。 */
    suspend fun probe(source: SourceConfig): Result<Long> {
        val started = System.currentTimeMillis()
        val query = "?ac=detail&pg=1"
        return when (val raw = fetch(source, "probe|${source.id}", query, 0L, forceNetwork = true)) {
            is Result.Ok -> Result.Ok(System.currentTimeMillis() - started)
            is Result.Err -> raw
        }
    }

    private suspend fun fetch(
        source: SourceConfig,
        cacheKey: String,
        query: String,
        ttlMs: Long,
        forceNetwork: Boolean = false,
    ): Result<String> {
        val cached = cache.read(cacheKey)
        if (!forceNetwork && cached != null && cache.isFresh(cached, ttlMs)) {
            return Result.Ok(cached.data)
        }

        var lastError: AppError? = null
        for (endpoint in endpoints(source)) {
            val url = endpoint + query
            try {
                val text = withTimeout(source.timeoutMs.coerceIn(1_000, 15_000).toLong()) {
                    Http.getText(url, source.headers)
                }
                cache.write(cacheKey, text)
                return Result.Ok(text)
            } catch (timeout: TimeoutCancellationException) {
                lastError = AppError(ErrorCodes.TIMEOUT, "请求超时：${hostOf(endpoint)}", timeout)
            } catch (t: Throwable) {
                val code = if (t is java.io.IOException) ErrorCodes.NETWORK else ErrorCodes.UNKNOWN
                lastError = AppError(code, "请求失败：${hostOf(endpoint)} ${t.message.orEmpty()}", t)
            }
            TvLog.d("MacCms", "端点失败 source=${source.id} url=$url err=${lastError?.msg}")
        }

        if (cached != null) {
            TvLog.w("MacCms", "网络失败，使用过期缓存 source=${source.id} key=$cacheKey")
            return Result.Ok(cached.data)
        }
        return Result.Err(lastError ?: AppError(ErrorCodes.SOURCE_OFFLINE, "没有可用端点：${source.name}"))
    }

    private fun endpoints(source: SourceConfig): List<String> {
        val bases = buildList {
            add(source.baseUrl)
            addAll(source.fallbackBaseUrls)
        }
        return bases.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.endsWith('/')) it else "$it/" }
            .distinct()
            .toList()
            .ifEmpty { listOf(source.baseUrl) }
    }

    private fun hostOf(endpoint: String): String = runCatching {
        java.net.URI(endpoint).host ?: endpoint
    }.getOrDefault(endpoint)

    enum class SourceSort { LATEST, HOT }

    companion object {
        private const val LIST_TTL_MS = 5 * 60 * 1000L
        private const val DETAIL_TTL_MS = 30 * 60 * 1000L
    }
}
