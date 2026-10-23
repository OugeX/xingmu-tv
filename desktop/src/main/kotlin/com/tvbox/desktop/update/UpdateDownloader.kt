package com.tvbox.desktop.update

import com.tvbox.desktop.core.Log
import com.tvbox.desktop.data.Http
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 更新包下载与校验：
 *  - 流式写入 `.part`，支持进度/取消；
 *  - 校验 Content-Length（服务端提供时）与文件头（msi OLE / exe MZ）；
 *  - 失败删除半成品并只重试一次；第二次失败抛异常，由 UpdateCenter 回 Idle 并写日志。
 */
object UpdateDownloader {

    private val MSI_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
    private val EXE_MAGIC = byteArrayOf(0x4D, 0x5A)
    private const val MAX_ATTEMPTS = 2

    data class Outcome(val file: Path, val bytes: Long, val attempts: Int)

    suspend fun download(
        http: Http,
        asset: ReleaseAsset,
        target: Path,
        kind: AssetKind,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): Outcome {
        var lastFailure: Throwable? = null
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        for (attempt in 1..MAX_ATTEMPTS) {
            runCatching { Files.deleteIfExists(part) }
            try {
                val result = http.downloadToFile(
                    url = asset.downloadUrl,
                    headers = emptyMap(),
                    target = part,
                    onProgress = onProgress,
                    cancelled = cancelled,
                )
                if (!contentLengthMatches(result.bytes, result.contentLength)) {
                    throw IOException("Content-Length 不匹配：期望 ${result.contentLength}，实际 ${result.bytes}")
                }
                if (!validateHeader(part, kind)) {
                    throw IOException("文件头校验失败（${asset.name}）")
                }
                target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
                return Outcome(target, result.bytes, attempt)
            } catch (cancel: CancellationException) {
                runCatching { Files.deleteIfExists(part) }
                throw cancel
            } catch (t: Throwable) {
                runCatching { Files.deleteIfExists(part) }
                lastFailure = t
                Log.w("Update", "下载失败 attempt=$attempt/${MAX_ATTEMPTS}: ${t.message ?: t.javaClass.simpleName}")
            }
        }
        throw lastFailure ?: IOException("更新包下载失败")
    }

    /** Content-Length 缺失（-1）时跳过校验；有则必须一致。 */
    fun contentLengthMatches(actualBytes: Long, contentLength: Long): Boolean =
        contentLength < 0L || actualBytes == contentLength

    /** msi 必须是 OLE `D0 CF 11 E0`，exe 必须是 `MZ`。 */
    fun validateHeader(file: Path, kind: AssetKind): Boolean {
        val magic = if (kind == AssetKind.MSI) MSI_MAGIC else EXE_MAGIC
        if (!Files.isRegularFile(file) || Files.size(file) < magic.size) return false
        val header = ByteArray(magic.size)
        Files.newInputStream(file).use { input ->
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) break
                read += n
            }
            if (read < magic.size) return false
        }
        return magic.indices.all { header[it] == magic[it] }
    }

    fun sanitizeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.')
        return safe.ifBlank { "XingmuTV-update.bin" }
    }
}
