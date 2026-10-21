package com.tvbox.app.source

import com.tvbox.app.core.ErrorCodes
import com.tvbox.app.core.Result
import com.tvbox.app.data.Channel
import com.tvbox.app.data.SourceKind
import kotlinx.coroutines.withTimeoutOrNull

/** 探测状态（持久化在 source.probe_state）。 */
enum class ProbeState { OK, DEGRADED, FAILED, UNVERIFIED }

data class ProbeOutcome(
    val sourceId: String,
    val state: ProbeState,
    val httpCode: Int? = null,
    val latencyMs: Long? = null,
    val classCount: Int? = null,
    val channelCount: Int? = null,
    val message: String? = null,
    val contentOk: Boolean = false,
    val checkedAt: Long = System.currentTimeMillis(),
) {
    val ok: Boolean get() = state == ProbeState.OK
}

/**
 * L3 连通探测 / L4 内容探测（docs/PLAN_SOURCES.md §4.1）。
 *
 * 原则：**导入永不因网络失败而失败**，探测失败只标记 UNVERIFIED/FAILED；
 * 探测超时 4 s、不重试（避免导入卡顿）。
 */
object SourceProbe {

    private const val PROBE_TIMEOUT_MS = 4000L
    private const val M3U_PREFIX_BYTES = 4096

    private data class CacheEntry(val at: Long, val channels: List<Channel>)

    private val m3uCache = HashMap<String, CacheEntry>()

    suspend fun probeL3(src: ParsedSource): ProbeOutcome = withTimeoutOrNull(PROBE_TIMEOUT_MS + 500) {
        when (src.kind) {
            SourceKind.MACCMS -> probeMaccms(src)
            SourceKind.M3U -> probeM3u(src)
            SourceKind.BUNDLE -> ProbeOutcome(src.id, ProbeState.UNVERIFIED, message = "bundle 不单独探测")
        }
    } ?: ProbeOutcome(src.id, ProbeState.FAILED, message = "探测超时")

    /** L4：maccms 取 1 条 detail 且 play_url 非空；m3u 解析出至少 1 个频道。 */
    suspend fun probeL4(src: ParsedSource): ProbeOutcome = withTimeoutOrNull(PROBE_TIMEOUT_MS + 1500) {
        when (src.kind) {
            SourceKind.MACCMS -> when (val r = MacCmsClient.probeContent(src)) {
                is Result.Err -> ProbeOutcome(
                    src.id,
                    if (r.error.code == ErrorCodes.NO_PLAY_URL) ProbeState.DEGRADED else ProbeState.FAILED,
                    message = r.error.msg,
                )
                is Result.Ok -> ProbeOutcome(
                    src.id,
                    ProbeState.OK,
                    latencyMs = r.value.second,
                    contentOk = true,
                )
            }
            SourceKind.M3U -> {
                val channels = fetchM3uChannels(src)
                if (channels.isEmpty()) {
                    ProbeOutcome(src.id, ProbeState.DEGRADED, message = "订阅里没有可用频道")
                } else {
                    ProbeOutcome(src.id, ProbeState.OK, channelCount = channels.size, contentOk = true)
                }
            }
            SourceKind.BUNDLE -> ProbeOutcome(src.id, ProbeState.UNVERIFIED, message = "bundle 不单独探测")
        }
    } ?: ProbeOutcome(src.id, ProbeState.FAILED, message = "内容探测超时")

    /** L3 + L4（“测试连通”按钮走这里），返回总延迟 ms。 */
    suspend fun probeFull(src: ParsedSource): Pair<ProbeOutcome, ProbeOutcome?> {
        val l3 = probeL3(src)
        if (!l3.ok) return l3 to null
        val l4 = probeL4(src)
        return l3 to l4
    }

    private suspend fun probeMaccms(src: ParsedSource): ProbeOutcome {
        return when (val r = MacCmsClient.fetchClasses(src)) {
            is Result.Err -> ProbeOutcome(src.id, ProbeState.FAILED, message = r.error.msg)
            is Result.Ok -> {
                val page = r.value
                when {
                    page.httpCode !in 200..299 ->
                        ProbeOutcome(src.id, ProbeState.FAILED, httpCode = page.httpCode, latencyMs = page.latencyMs, message = "HTTP ${page.httpCode}")
                    page.code != 1 ->
                        ProbeOutcome(src.id, ProbeState.DEGRADED, httpCode = page.httpCode, latencyMs = page.latencyMs, message = "接口 code=${page.code}")
                    page.categories.isEmpty() ->
                        ProbeOutcome(src.id, ProbeState.DEGRADED, httpCode = page.httpCode, latencyMs = page.latencyMs, classCount = 0, message = "分类树为空")
                    else -> ProbeOutcome(
                        src.id,
                        ProbeState.OK,
                        httpCode = page.httpCode,
                        latencyMs = page.latencyMs,
                        classCount = page.categories.size,
                    )
                }
            }
        }
    }

    private suspend fun probeM3u(src: ParsedSource): ProbeOutcome {
        if (src.inline != null) {
            val parsed = M3uParser.parse(src.inline, src.id)
            val channels = M3uParser.mergeChannelDefs(
                M3uParser.applyFilters(parsed.channels, src.filters),
                src.channelDefs,
                src.id,
            )
            return if (channels.isEmpty()) {
                ProbeOutcome(src.id, ProbeState.DEGRADED, channelCount = 0, message = "内联 m3u 无可用频道")
            } else {
                ProbeOutcome(src.id, ProbeState.OK, channelCount = channels.size, latencyMs = 0)
            }
        }
        val url = src.m3uUrl ?: return ProbeOutcome(src.id, ProbeState.FAILED, message = "缺少订阅地址")
        val r = HttpLayer.getText(
            url = url,
            headers = src.headers,
            timeoutMs = src.timeoutMs.toLong(),
            maxBytes = M3U_PREFIX_BYTES,
        )
        return when (r) {
            is Result.Err -> ProbeOutcome(src.id, ProbeState.FAILED, message = r.error.msg)
            is Result.Ok -> {
                val body = r.value
                when {
                    body.httpCode !in 200..299 ->
                        ProbeOutcome(src.id, ProbeState.FAILED, httpCode = body.httpCode, latencyMs = body.latencyMs, message = "HTTP ${body.httpCode}")
                    !body.body.contains("#EXTM3U", ignoreCase = true) ->
                        ProbeOutcome(src.id, ProbeState.DEGRADED, httpCode = body.httpCode, latencyMs = body.latencyMs, message = "响应缺少 #EXTM3U 头")
                    else -> ProbeOutcome(src.id, ProbeState.OK, httpCode = body.httpCode, latencyMs = body.latencyMs)
                }
            }
        }
    }

    /**
     * 读取 m3u 源的全部频道（inline 直接解析，url 拉取 ≤ maxBytes）。
     * 结果缓存 10 分钟，避免 t4/t9 每次进页面都拉 2MB。
     */
    suspend fun fetchM3uChannels(src: ParsedSource): List<Channel> {
        if (src.inline != null) {
            val parsed = M3uParser.parse(src.inline, src.id)
            return M3uParser.mergeChannelDefs(
                M3uParser.applyFilters(parsed.channels, src.filters),
                src.channelDefs,
                src.id,
            )
        }
        val url = src.m3uUrl ?: return emptyList()
        synchronized(m3uCache) {
            val hit = m3uCache[url]
            if (hit != null && System.currentTimeMillis() - hit.at < 10 * 60_000L) return hit.channels
        }
        val r = HttpLayer.getText(
            url = url,
            headers = src.headers,
            timeoutMs = src.timeoutMs.toLong(),
            maxBytes = src.maxBytes,
        )
        if (r !is Result.Ok) return emptyList()
        val text = r.value.body
        val parsed = M3uParser.parse(text, src.id)
        val channels = M3uParser.mergeChannelDefs(
            M3uParser.applyFilters(parsed.channels, src.filters),
            src.channelDefs,
            src.id,
        )
        if (channels.isNotEmpty()) {
            synchronized(m3uCache) { m3uCache[url] = CacheEntry(System.currentTimeMillis(), channels) }
        }
        return channels
    }

    fun clearCache() {
        synchronized(m3uCache) { m3uCache.clear() }
    }
}
