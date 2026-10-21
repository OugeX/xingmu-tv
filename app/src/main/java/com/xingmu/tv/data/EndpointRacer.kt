package com.xingmu.tv.data

import android.content.Context
import com.xingmu.tv.core.AppDispatchers
import com.xingmu.tv.core.TvLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 端点竞速 + 本机首选记忆（t23）。
 *
 * 背景：真机访问 macapi1.com 详情 17–70s，而备用端点可正常返回；
 * 因此每个源在首次请求时对 baseUrl + fallbackBaseUrls 并发一个轻量探测
 * （`ac=list&pg=1`，3s 截止），取最先成功响应者；结果写 SharedPreferences，
 * 30 分钟内直接用上次 winner，失败再重新竞速/降级。
 *
 * 注意：探测跑在 IO，绝不阻塞主线程；只用于 MacCMS JSON 端点，不涉及播放 m3u8。
 */
class EndpointRacer(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("tvbox_endpoint_race", Context.MODE_PRIVATE)
    private val memo = ConcurrentHashMap<String, Winner>()
    private val inFlight = HashMap<String, kotlinx.coroutines.Deferred<Winner?>>()
    private val inFlightLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Winner(val endpoint: String, val ms: Long, val measuredAt: Long)

    /** 返回按优先级排序的端点（winner 在前），并确保已经完成一次竞速。 */
    suspend fun orderedEndpoints(source: SourceConfig): List<String> {
        val endpoints = endpointsOf(source)
        if (endpoints.size <= 1) return endpoints
        val known = memo[source.id] ?: readPersisted(source.id)
        val fresh = known != null && System.currentTimeMillis() - known.measuredAt <= FRESH_MS &&
            endpoints.contains(known.endpoint)
        if (!fresh) {
            val existing = synchronized(inFlightLock) { inFlight[source.id] }
            val deferred = existing ?: scope.async {
                try {
                    race(source, endpoints)
                } finally {
                    synchronized(inFlightLock) { inFlight.remove(source.id) }
                }
            }.also { created ->
                synchronized(inFlightLock) { inFlight[source.id] = created }
            }
            // 只等一小会儿：竞速是为了后续请求更快，绝不能把首屏拖住 3–6s。
            // 超时后竞速仍在后台跑，本轮先用已知顺序（persisted winner / baseUrl）。
            withTimeoutOrNull(RACE_GRACE_MS) { deferred.await() }
        }
        val winner = memo[source.id] ?: readPersisted(source.id)
        return if (winner != null && endpoints.contains(winner.endpoint)) {
            listOf(winner.endpoint) + endpoints.filter { it != winner.endpoint }
        } else {
            endpoints
        }
    }

    fun markFailed(sourceId: String) {
        memo.remove(sourceId)
        prefs.edit().remove(keyWinner(sourceId)).apply()
    }

    /** 详情竞速里实测出来的赢家，立刻记下来，下次同源请求直接优先它。 */
    fun markWinner(sourceId: String, endpoint: String) {
        val winner = Winner(endpoint, 0L, System.currentTimeMillis())
        memo[sourceId] = winner
        persist(sourceId, winner)
        TvLog.i("EndpointRace", "endpoint_race winner=$endpoint ms=hedged source=$sourceId")
    }

    private suspend fun race(source: SourceConfig, endpoints: List<String>): Winner? = coroutineScope {
        val channel = Channel<Winner>(Channel.UNLIMITED)
        val jobs = ArrayList<Job>()
        for (endpoint in endpoints) {
            jobs += launch {
                val started = System.currentTimeMillis()
                val startedAt = System.currentTimeMillis()
                val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    // 读完整的小响应（ids=1 约 6KB），把 TLS/连接放回连接池，后续详情可复用
                    runCatching {
                        Http.getText(endpoint + "?ac=detail&ids=1&pg=1", source.headers)
                    }.isSuccess
                } ?: false
                if (ok) {
                    channel.send(Winner(endpoint, System.currentTimeMillis() - startedAt, System.currentTimeMillis()))
                }
            }
        }
        val first = withTimeoutOrNull(PROBE_TIMEOUT_MS + 300L) { channel.receive() }
        jobs.forEach { it.cancel() }
        if (first != null) {
            // 详情竞速往往比探测更早拿到真实赢家，别被探测结果覆盖
            if (memo[source.id] == null) {
                memo[source.id] = first
                persist(source.id, first)
            }
            TvLog.i("EndpointRace", "endpoint_race winner=${first.endpoint} ms=${first.ms} source=${source.id}")
        } else {
            TvLog.w("EndpointRace", "endpoint_race all failed source=${source.id} endpoints=${endpoints.size}")
        }
        first
    }

    private fun endpointsOf(source: SourceConfig): List<String> = buildList {
        add(source.baseUrl)
        addAll(source.fallbackBaseUrls)
    }.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { if (it.endsWith('/')) it else "$it/" }
        .distinct()
        .toList()
        .ifEmpty { listOf(source.baseUrl) }

    private fun persist(sourceId: String, winner: Winner) {
        prefs.edit()
            .putString(keyWinner(sourceId), winner.endpoint)
            .putLong(keyMs(sourceId), winner.ms)
            .putLong(keyAt(sourceId), winner.measuredAt)
            .apply()
    }

    private fun readPersisted(sourceId: String): Winner? {
        val endpoint = prefs.getString(keyWinner(sourceId), null) ?: return null
        val ms = prefs.getLong(keyMs(sourceId), -1L)
        val at = prefs.getLong(keyAt(sourceId), 0L)
        return if (ms >= 0 && at > 0) Winner(endpoint, ms, at) else null
    }

    private fun keyWinner(id: String) = "winner.$id"
    private fun keyMs(id: String) = "ms.$id"
    private fun keyAt(id: String) = "at.$id"

    private companion object {
        // 600ms 内拿到 winner 就直接用；否则先按已知顺序发请求，竞速结果留给下一次
        const val RACE_GRACE_MS = 0L
        // 真机 TTFB 2–4s，探测窗口必须覆盖，否则永远 all failed（probe 在后台跑，不阻塞 UI）
        const val PROBE_TIMEOUT_MS = 6_000L
        const val FRESH_MS = 30 * 60 * 1000L
    }
}
