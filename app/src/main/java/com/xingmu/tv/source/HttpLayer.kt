package com.xingmu.tv.source

import com.xingmu.tv.core.AppDispatchers
import com.xingmu.tv.core.AppError
import com.xingmu.tv.core.ErrorCodes
import com.xingmu.tv.core.Result
import com.xingmu.tv.core.TvLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 唯一的网络出口（OkHttp 4.12，gzip 由 OkHttp 自动协商）。
 *
 * - 响应体按 [maxBytes] 截断读取，防大响应/OOM（PLAN_SOURCES §11）；
 * - 按超时值复用 client（共享连接池/Dispatcher），避免每请求 newBuilder；
 * - 协程取消会立即 `call.cancel()`（聚合搜索 3.5s 截止时不再白占 IO 线程）；
 * - 全部在 [AppDispatchers.Io] 执行，调用方（Repository）无需切线程。
 */
object HttpLayer {

    private const val TAG = "Http"

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val clientByTimeout = ConcurrentHashMap<Long, OkHttpClient>()

    private fun clientFor(timeoutMs: Long): OkHttpClient =
        clientByTimeout.getOrPut(timeoutMs) {
            baseClient.newBuilder()
                .connectTimeout(minOf(timeoutMs, 5000L), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs + 2000L, TimeUnit.MILLISECONDS)
                .build()
        }

    data class TextResponse(
        val httpCode: Int,
        val body: String,
        val latencyMs: Long,
        val etag: String?,
    )

    /** GET 文本（最多 [maxBytes] 字节）。HTTP 非 2xx 也返回 Ok，由调用方判断（探测需要状态码）。 */
    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 6000,
        maxBytes: Int = 2 * 1024 * 1024,
        userAgent: String = "okhttp/4.12.0",
    ): Result<TextResponse> = withContext(AppDispatchers.Io) {
        val started = System.currentTimeMillis()
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, text/plain, */*")
        for ((k, v) in headers) {
            if (UrlRules.isHeaderKey(k) && !v.contains('\r') && !v.contains('\n')) builder.header(k, v)
        }
        val request = try {
            builder.build()
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure(ErrorCodes.SOURCE_INVALID, "地址非法：${UrlRules.maskCredentials(url)}")
        }
        val call = clientFor(timeoutMs).newCall(request)
        try {
            val resp = call.await()
            resp.use { r ->
                val body = r.body ?: return@withContext Result.failure(ErrorCodes.IO, "响应为空")
                val stream = body.byteStream()
                val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    val keep = minOf(n, maxBytes - total)
                    if (keep > 0) {
                        out.write(buf, 0, keep)
                        total += keep
                    }
                    if (total >= maxBytes) break
                }
                val text = String(out.toByteArray(), Charsets.UTF_8)
                val latency = System.currentTimeMillis() - started
                TvLog.d(TAG, "GET ${UrlRules.maskCredentials(url)} -> ${r.code} ${total}B ${latency}ms")
                Result.Ok(TextResponse(r.code, text, latency, r.header("ETag")))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 上游超时/取消（聚合 3.5s 截止、t4 的 2.8s 兜底包装）——原样抛，不记 E 日志
            throw e
        } catch (e: SocketTimeoutException) {
            TvLog.w(TAG, "timeout ${UrlRules.maskCredentials(url)}", e)
            Result.failure(ErrorCodes.TIMEOUT, "请求超时（${timeoutMs}ms）")
        } catch (e: IOException) {
            TvLog.w(TAG, "io ${UrlRules.maskCredentials(url)}: ${e.message}")
            Result.failure(AppError(ErrorCodes.NETWORK, "网络不可用或连接失败", e))
        } catch (e: Exception) {
            TvLog.e(TAG, "unexpected", e)
            Result.failure(AppError(ErrorCodes.UNKNOWN, "请求失败：${e.message}", e))
        }
    }

    /** 可取消的异步执行：协程取消 → call.cancel()，避免超时后线程仍等网络。 */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            },
        )
    }
}
