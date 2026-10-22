package com.xingmu.tv.update

import com.xingmu.tv.core.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * APK 下载（UPDATE_SPEC §3）：流式写 `.part` → 校验字节数 + ZIP 魔数 → 改名。
 * 取消（协程取消）会同时 cancel OkHttp Call，避免后台继续吃流量。
 */
internal object ApkDownloader {

    private const val BUFFER = 64 * 1024
    private const val PROGRESS_STEP = 256 * 1024

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun download(
        url: String,
        target: File,
        expectedBytes: Long,
        onProgress: (read: Long, total: Long) -> Unit,
    ): File = withContext(AppDispatchers.Io) {
        val temp = File(target.parentFile, target.name + ".part")
        temp.delete()
        val call = http.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "XingmuTV-updater")
                .get()
                .build(),
        )
        // 协程取消 → cancel OkHttp Call（Job 只有 invokeOnCompletion，取消态用 isCancelled 区分）
        val job = coroutineContext[Job]
        val cancellation = job?.invokeOnCompletion {
            if (job.isCancelled) runCatching { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: expectedBytes
                var read = 0L
                var lastProgress = 0L
                temp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (isActive) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            read += n
                            if (read - lastProgress >= PROGRESS_STEP || (total in 1 until read)) {
                                lastProgress = read
                                onProgress(read, total)
                            }
                        }
                    }
                    out.fd.sync()
                }
                if (!isActive) throw CancellationException("download cancelled")
                if (total > 0 && read != total) throw IOException("size mismatch $read/$total")
                if (!isApk(temp)) throw IOException("not an apk")
                onProgress(read, total)
            }
            if (!temp.renameTo(target)) throw IOException("rename failed")
            target
        } finally {
            cancellation?.dispose()
            if (temp.exists()) temp.delete()
        }
    }

    private fun isApk(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)
}
