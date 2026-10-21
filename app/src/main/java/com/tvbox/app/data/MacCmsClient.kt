package com.tvbox.app.data

import com.tvbox.app.core.AppError
import com.tvbox.app.core.ErrorCodes
import com.tvbox.app.core.Result
import com.tvbox.app.core.TvLog
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

/**
 * MacCMS 站点客户端：负责 URL 组装、多端点降级、文件缓存与 JSON 解析。
 *
 * 端点必须带结尾斜杠（不带会被 307 到 http，见 docs/TOOLCHAIN.md）；
 * 多端点顺序：baseUrl → fallbackBaseUrls。
 */
class MacCmsClient(
    private val cache: FileCache,
    private val racer: EndpointRacer? = null,
) {
    /** 8 条内存详情缓存（t22 语义，t23 保留）：命中直接返回，连开详情不再发网络请求。 */
    private val detailMemo = object : LinkedHashMap<String, VodDetail>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VodDetail>?): Boolean =
            size > DETAIL_MEMO_SIZE
    }


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

    suspend fun detail(
        source: SourceConfig,
        vodId: String,
        onRaw: ((String) -> Unit)? = null,
    ): Result<VodDetail> {
        val memoKey = "${source.id}|$vodId"
        synchronized(detailMemo) { detailMemo[memoKey] }?.let {
            TvLog.i("DetailPerf", "memory hit source=${source.id} vod=$vodId")
            return Result.Ok(it)
        }
        val query = "?ac=detail&ids=${URLEncoder.encode(vodId, "UTF-8")}&pg=1"
        val started = System.currentTimeMillis()
        return when (val raw = fetch(
            source = source,
            cacheKey = "detail|${source.id}|$vodId",
            query = query,
            ttlMs = DETAIL_TTL_MS,
            timeoutMs = DETAIL_TIMEOUT_MS,
            hedge = true,
        )) {
            is Result.Ok -> when (val parsed = MacCmsParser.parseDetail(raw.value, source.id, vodId)) {
                is Result.Ok -> {
                    synchronized(detailMemo) { detailMemo[memoKey] = parsed.value }
                    TvLog.i("DetailPerf", "loaded source=${source.id} vod=$vodId in=${System.currentTimeMillis() - started}ms")
                    // t27：给预置详情表一个回写原始 JSON 的机会（7 天过期后的静默刷新）
                    runCatching { onRaw?.invoke(raw.value) }
                    parsed
                }
                is Result.Err -> parsed
            }
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
        timeoutMs: Long = source.timeoutMs.coerceIn(1_000, 15_000).toLong(),
        maxAttempts: Int = Int.MAX_VALUE,
        hedge: Boolean = false,
    ): Result<String> {
        val cached = cache.read(cacheKey)
        if (!forceNetwork && cached != null && cache.isFresh(cached, ttlMs)) {
            TvLog.i("DetailPerf", "file cache hit key=$cacheKey age=${System.currentTimeMillis() - cached.savedAtMs}ms")
            return Result.Ok(cached.data)
        }

        val ordered = racer?.orderedEndpoints(source) ?: endpoints(source)
        val started = System.currentTimeMillis()
        val result = if (hedge && ordered.size > 1) {
            // 详情竞速：真机单端点 TTFB 3.7–6.8s 且抖动大，串行降级会把 6.8s 端点排在前面；
            // 多端点并发同一 ids 查询（约 6KB），最快者胜出，其余立即取消。
            hedgeFetch(source, ordered, query, timeoutMs)
        } else {
            sequentialFetch(source, ordered, query, timeoutMs, maxAttempts)
        }

        if (result is Result.Ok) {
            cache.write(cacheKey, result.value)
            TvLog.i("DetailPerf", "fetched key=$cacheKey in=${System.currentTimeMillis() - started}ms")
            return result
        }

        if (cached != null) {
            TvLog.w("MacCms", "网络失败，使用过期缓存 source=${source.id} key=$cacheKey")
            return Result.Ok(cached.data)
        }
        return result
    }

    private suspend fun sequentialFetch(
        source: SourceConfig,
        ordered: List<String>,
        query: String,
        timeoutMs: Long,
        maxAttempts: Int,
    ): Result<String> {
        var lastError: AppError? = null
        for ((index, endpoint) in ordered.withIndex()) {
            if (index >= maxAttempts) break
            val url = endpoint + query
            try {
                val text = withTimeout(timeoutMs) { Http.getText(url, source.headers) }
                return Result.Ok(text)
            } catch (timeout: TimeoutCancellationException) {
                lastError = AppError(ErrorCodes.TIMEOUT, "网络较慢，请重试（${hostOf(endpoint)} 超时）", timeout)
                racer?.markFailed(source.id)
            } catch (t: Throwable) {
                val code = if (t is java.io.IOException) ErrorCodes.NETWORK else ErrorCodes.UNKNOWN
                lastError = AppError(code, "网络较慢，请重试（${hostOf(endpoint)}）", t)
                racer?.markFailed(source.id)
            }
            TvLog.d("MacCms", "端点失败 source=${source.id} url=$url err=${lastError?.msg}")
        }
        return Result.Err(
            lastError ?: AppError(
                ErrorCodes.NETWORK,
                "网络较慢，请重试（没有可用端点：${source.name}）",
            ),
        )
    }

    /** 多端点并发同一查询，首个成功者胜出；全部失败返回最后一个错误。 */
    private suspend fun hedgeFetch(
        source: SourceConfig,
        ordered: List<String>,
        query: String,
        timeoutMs: Long,
    ): Result<String> = coroutineScope {
        val channel = Channel<HedgeResult>(Channel.UNLIMITED)
        val jobs = ordered.map { endpoint ->
            launch {
                val result = runCatching { withTimeout(timeoutMs) { Http.getText(endpoint + query, source.headers) } }
                result.fold(
                    onSuccess = { text -> channel.send(HedgeResult(endpoint, text, null)) },
                    onFailure = { error -> channel.send(HedgeResult(endpoint, null, error)) },
                )
            }
        }
        val firstOk = withTimeoutOrNull(timeoutMs + 300L) {
            var lastError: Throwable? = null
            var lastEndpoint = ordered.first()
            for (i in ordered.indices) {
                val r = channel.receive()
                if (r.text != null) return@withTimeoutOrNull r
                lastError = r.error
                lastEndpoint = r.endpoint
            }
            HedgeResult(lastEndpoint, null, lastError)
        }
        jobs.forEach { it.cancel() }
        when {
            firstOk == null -> Result.Err(AppError(ErrorCodes.TIMEOUT, "网络较慢，请重试（详情请求超时）"))
            firstOk.text != null -> {
                racer?.markWinner(source.id, firstOk.endpoint)
                Result.Ok(firstOk.text)
            }
            else -> {
                racer?.markFailed(source.id)
                val error = firstOk.error
                Result.Err(
                    AppError(
                        if (error is TimeoutCancellationException) ErrorCodes.TIMEOUT else ErrorCodes.NETWORK,
                        "网络较慢，请重试（${hostOf(firstOk.endpoint)}）",
                        error,
                    ),
                )
            }
        }
    }

    private data class HedgeResult(val endpoint: String, val text: String?, val error: Throwable?)

    private suspend fun endpoints(source: SourceConfig): List<String> = racer?.orderedEndpoints(source)
        ?: buildList {
            add(source.baseUrl)
            addAll(source.fallbackBaseUrls)
        }.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.endsWith('/')) it else "$it/" }
            .distinct()
            .toList()
            .ifEmpty { listOf(source.baseUrl) }

    private fun hostOf(endpoint: String): String = runCatching {
        java.net.URI(endpoint).host ?: endpoint
    }.getOrDefault(endpoint)

    enum class SourceSort { LATEST, HOT }

    companion object {
        private const val LIST_TTL_MS = 5 * 60 * 1000L
        // t23：详情持久化 6 小时，命中文件缓存直接秒开（内存缓存 8 条在 detail() 内）
        private const val DETAIL_TTL_MS = 6 * 60 * 60 * 1000L
        private const val DETAIL_TIMEOUT_MS = 8_000L
        private const val DETAIL_MEMO_SIZE = 8
    }
}
