package com.tvbox.desktop.data

import com.tvbox.desktop.core.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.URLEncoder

/**
 * MacCMS 站点客户端：URL 组装 + 多端点降级 + SWR 文件缓存。
 * 端点必须带结尾斜杠（不带会被 307，见 docs/TOOLCHAIN.md）。
 */
class MacCmsClient(
    private val http: Http,
    private val cache: FileCache,
) {
    data class Outcome<T>(
        val value: T?,
        val error: SourceError? = null,
        val stale: Boolean = false,
    ) {
        val ok: Boolean get() = value != null
    }

    data class SourceError(val code: String, val message: String)

    suspend fun list(source: SourceConfig, category: String, page: Int): Outcome<List<VodSummary>> {
        val typeId = typeIdFor(source, category) ?: return Outcome(
            null,
            SourceError("E_CATEGORY", "源 ${source.name} 未配置分类 $category"),
        )
        val query = "?ac=detail&t=$typeId&pg=${page.coerceAtLeast(1)}&by=time"
        return when (val raw = fetch(source, "list|${source.id}|$typeId|$page", query, LIST_TTL_MS)) {
            is Fetch.Ok -> try {
                Outcome(MacCmsParser.parseList(raw.text, source), stale = raw.stale)
            } catch (t: Throwable) {
                Outcome(null, SourceError("PARSE", "列表解析失败：${t.message}"))
            }
            is Fetch.Err -> Outcome(null, raw.error)
        }
    }

    suspend fun search(source: SourceConfig, keyword: String, page: Int): Outcome<List<VodSummary>> {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val query = "?ac=detail&wd=$encoded&pg=${page.coerceAtLeast(1)}"
        return when (val raw = fetch(source, "search|${source.id}|$encoded|$page", query, LIST_TTL_MS)) {
            is Fetch.Ok -> try {
                Outcome(MacCmsParser.parseList(raw.text, source), stale = raw.stale)
            } catch (t: Throwable) {
                Outcome(null, SourceError("PARSE", "搜索解析失败：${t.message}"))
            }
            is Fetch.Err -> Outcome(null, raw.error)
        }
    }

    suspend fun detail(source: SourceConfig, vodId: String): Outcome<VodDetail> {
        val query = "?ac=detail&ids=${URLEncoder.encode(vodId, "UTF-8")}&pg=1"
        return when (val raw = fetch(source, "detail|${source.id}|$vodId", query, DETAIL_TTL_MS)) {
            is Fetch.Ok -> try {
                Outcome(MacCmsParser.parseDetail(raw.text, source, vodId), stale = raw.stale)
            } catch (t: Throwable) {
                Outcome(null, SourceError("PARSE", "详情解析失败：${t.message}"))
            }
            is Fetch.Err -> Outcome(null, raw.error)
        }
    }

    suspend fun probe(source: SourceConfig): Outcome<Long> {
        val started = System.currentTimeMillis()
        return when (val raw = fetch(source, "probe|${source.id}", "?ac=detail&pg=1", 0, forceNetwork = true)) {
            is Fetch.Ok -> Outcome(System.currentTimeMillis() - started)
            is Fetch.Err -> Outcome(null, raw.error)
        }
    }

    private sealed interface Fetch {
        data class Ok(val text: String, val stale: Boolean) : Fetch
        data class Err(val error: SourceError) : Fetch
    }

    private suspend fun fetch(
        source: SourceConfig,
        cacheKey: String,
        query: String,
        ttlMs: Long,
        forceNetwork: Boolean = false,
    ): Fetch {
        val cached = cache.read(cacheKey)
        if (!forceNetwork && cached != null && cache.isFresh(cached, ttlMs)) {
            return Fetch.Ok(cached.data, stale = false)
        }
        var lastError: SourceError? = null
        for (endpoint in endpoints(source)) {
            val url = endpoint + query
            try {
                val text = withTimeout(source.timeoutMs.coerceIn(1_000, 15_000).toLong()) {
                    http.getText(url, source.headers, source.timeoutMs, maxBytes = 8 * 1024 * 1024)
                }
                cache.write(cacheKey, text)
                return Fetch.Ok(text, stale = false)
            } catch (timeout: TimeoutCancellationException) {
                lastError = SourceError("TIMEOUT", "请求超时：${hostOf(endpoint)}")
            } catch (t: HttpException) {
                lastError = SourceError(t.kind, "${hostOf(endpoint)}：${t.message}")
            } catch (t: Throwable) {
                lastError = SourceError("NETWORK", "${hostOf(endpoint)}：${t.message ?: t.javaClass.simpleName}")
            }
            Log.d("MacCms", "端点失败 source=${source.id} url=$url err=${lastError?.message}")
        }
        if (cached != null) {
            Log.w("MacCms", "网络失败，使用过期缓存 source=${source.id} key=$cacheKey")
            return Fetch.Ok(cached.data, stale = true)
        }
        return Fetch.Err(lastError ?: SourceError("SOURCE_OFFLINE", "没有可用端点：${source.name}"))
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

    private fun typeIdFor(source: SourceConfig, category: String): Int? {
        source.categories[category]?.firstOrNull()?.let { return it }
        return DEFAULT_TYPE_IDS[category]
    }

    companion object {
        private const val LIST_TTL_MS = 5 * 60 * 1000L
        private const val DETAIL_TTL_MS = 30 * 60 * 1000L

        /** MacCMS 通用分类号，仅当源未配置 categories 时兜底。 */
        val DEFAULT_TYPE_IDS = mapOf(
            "MOVIE" to 1,
            "TV" to 2,
            "VARIETY" to 3,
            "ANIME" to 4,
            "SHORT" to 5,
            "SPORTS" to 6,
            "OTHER" to 7,
        )
    }
}
