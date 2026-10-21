package com.tvbox.desktop.data

import com.tvbox.desktop.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

class HttpException(val kind: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 网络层：JDK 内置 HttpClient（不依赖 Android/OkHttp），UA / Referer 与 Android 端一致。
 */
class Http(
    private val defaultUa: String = DEFAULT_UA,
    private val defaultReferer: String = DEFAULT_REFERER,
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    fun headersWithDefaults(headers: Map<String, String>): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged["User-Agent"] = defaultUa
        merged["Referer"] = defaultReferer
        headers.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank()) {
                val existing = merged.keys.firstOrNull { it.equals(k, ignoreCase = true) }
                if (existing != null) merged.remove(existing)
                merged[k] = v
            }
        }
        return merged
    }

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20_000,
        maxBytes: Int = 8 * 1024 * 1024,
    ): String = withContext(Dispatchers.IO) {
        val bytes = download(url, headers, timeoutMs, maxBytes)
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) throw HttpException("EMPTY", "空响应：$url")
        text
    }

    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20_000,
        maxBytes: Int = 8 * 1024 * 1024,
    ): ByteArray = withContext(Dispatchers.IO) { download(url, headers, timeoutMs, maxBytes) }

    private fun download(url: String, headers: Map<String, String>, timeoutMs: Int, maxBytes: Int): ByteArray {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs.coerceIn(1_000, 60_000).toLong()))
            .GET()
        headersWithDefaults(headers).forEach { (k, v) -> runCatching { builder.header(k, v) } }
        val response: HttpResponse<ByteArray>
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        } catch (t: java.net.http.HttpTimeoutException) {
            throw HttpException("TIMEOUT", "请求超时：${hostOf(url)}", t)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            throw HttpException("NETWORK", "请求被中断：$url", t)
        } catch (t: Exception) {
            throw HttpException("NETWORK", "网络失败：${hostOf(url)} ${t.message.orEmpty()}", t)
        }
        if (response.statusCode() !in 200..299) {
            throw HttpException("HTTP", "HTTP ${response.statusCode()}：${hostOf(url)}")
        }
        val body = response.body() ?: ByteArray(0)
        if (body.size > maxBytes) throw HttpException("TOO_LARGE", "响应过大（${body.size} 字节）：$url")
        return body
    }

    private fun hostOf(url: String): String = runCatching { URI.create(url).host ?: url }.getOrDefault(url)

    companion object {
        /** 与 Android 端 Http.DEFAULT_UA 完全一致 */
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        const val DEFAULT_REFERER = "https://bfzy1.tv/"
    }
}
