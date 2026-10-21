package com.xingmu.tv.data

import com.xingmu.tv.core.AppDispatchers
import com.xingmu.tv.core.AppError
import com.xingmu.tv.core.ErrorCodes
import com.xingmu.tv.core.Result
import com.xingmu.tv.core.TvLog
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 全 App 共享的 OkHttpClient（API / 播放列表 / Coil 图片共用连接池，见 PLAN_ARCHITECTURE §3.3）。
 * 禁止在 UI 里 new client。
 */
object Http {

    const val DEFAULT_UA: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    /** 点播源实测需要 Referer，缺省给主站，源配置可按需覆盖。 */
    const val DEFAULT_REFERER = "https://bfzy1.tv/"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 6
                    maxRequestsPerHost = 4
                },
            )
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun buildRequest(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url).get()
        val hasUa = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
        val hasReferer = headers.keys.any { it.equals("Referer", ignoreCase = true) }
        if (!hasUa) builder.header("User-Agent", DEFAULT_UA)
        if (!hasReferer) builder.header("Referer", DEFAULT_REFERER)
        for ((k, v) in headers) {
            if (k.isBlank() || v.isBlank()) continue
            runCatching { builder.header(k, v) }
        }
        return builder.build()
    }

    /** 可取消的 GET；取消时立即 cancel OkHttp Call，避免超时后线程仍占着连接。 */
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        suspendCancellableCoroutine { cont ->
            val call: Call = client.newCall(buildRequest(url, headers))
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { r ->
                            try {
                                if (!r.isSuccessful) {
                                    throw IOException("HTTP ${r.code} ${r.message}")
                                }
                                val body = r.body?.string()
                                if (body.isNullOrBlank()) throw IOException("空响应体")
                                if (cont.isActive) cont.resume(body)
                            } catch (t: Throwable) {
                                if (cont.isActive) cont.resumeWithException(t)
                            }
                        }
                    }
                },
            )
        }

    /**
     * 轻量 TTFB 探测（t23 端点竞速）：只等响应头，不读 body，立即 close/cancel。
     * 避免为了测速把 45–164KB 的 JSON 全下载下来。
     */
    suspend fun probeLatencyMs(url: String, headers: Map<String, String>, timeoutMs: Long): Long? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Long> { cont ->
                val started = System.nanoTime()
                val call = client.newCall(buildRequest(url, headers))
                cont.invokeOnCancellation { runCatching { call.cancel() } }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val elapsed = (System.nanoTime() - started) / 1_000_000L
                            val ok = response.isSuccessful
                            response.close()
                            runCatching { call.cancel() }
                            if (cont.isActive) {
                                if (ok) cont.resume(elapsed) else cont.resumeWithException(
                                    IOException("HTTP ${response.code}"),
                                )
                            }
                        }
                    },
                )
            }
        }

    /**
     * 下载到文件（t27 预置详情包）：边读边写，回调进度；协程取消时 cancel Call。
     * 返回写入字节数。-1 表示服务端没给 Content-Length。
     */
    suspend fun downloadTo(
        url: String,
        dest: java.io.File,
        onProgress: (read: Long, total: Long) -> Unit = { _, _ -> },
    ): Long = withContext(AppDispatchers.Io) {
        val call = client.newCall(buildRequest(url, emptyMap()))
        val response = try {
            call.execute()
        } catch (t: Throwable) {
            call.cancel()
            throw t
        }
        response.use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} ${r.message}")
            val body = r.body ?: throw IOException("空响应体")
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            dest.outputStream().buffered().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) {                            call.cancel()
                            throw java.io.InterruptedIOException("下载已取消")
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
            total
        }
    }

    /** 内部工具：把异常折成带错误码的 Result。 */
    suspend fun <T> ioCall(block: suspend () -> T): Result<T> = withContext(AppDispatchers.Io) {        try {
            Result.Ok(block())
        } catch (t: Throwable) {
            val code = if (t is java.net.SocketTimeoutException || t is java.util.concurrent.TimeoutException) {
                ErrorCodes.TIMEOUT
            } else if (t is IOException) {
                ErrorCodes.NETWORK
            } else {
                ErrorCodes.UNKNOWN
            }
            TvLog.w("Http", "请求失败: ${t.message}", t)
            Result.Err(AppError(code, "网络请求失败：${t.message ?: t.javaClass.simpleName}", t))
        }
    }
}
