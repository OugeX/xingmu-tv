package com.tvbox.desktop.data

import com.tvbox.desktop.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 文件级网络缓存（SWR 的落盘层）：
 * - 网络成功覆盖写（先写 .tmp 再原子 rename）；
 * - 网络失败由调用方回退读取旧内容（stale）；
 * - 单条上限 4MB，避免异常响应撑爆磁盘。
 */
class FileCache(private val directory: Path) {

    data class Entry(val data: String, val savedAtMs: Long)

    init {
        runCatching { Files.createDirectories(directory) }
    }

    suspend fun read(key: String): Entry? = withContext(Dispatchers.IO) {
        try {
            val file = fileFor(key)
            if (!Files.isRegularFile(file)) return@withContext null
            if (Files.size(file) > MAX_BYTES) {
                runCatching { Files.delete(file) }
                return@withContext null
            }
            Entry(Files.readString(file, StandardCharsets.UTF_8), Files.getLastModifiedTime(file).toMillis())
        } catch (t: Throwable) {
            Log.w("FileCache", "读缓存失败 key=$key: ${t.message}")
            null
        }
    }

    suspend fun write(key: String, data: String) = withContext(Dispatchers.IO) {
        try {
            if (data.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) return@withContext
            Files.createDirectories(directory)
            val target = fileFor(key)
            val tmp = directory.resolve(target.fileName.toString() + ".tmp")
            Files.writeString(tmp, data, StandardCharsets.UTF_8)
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            Log.w("FileCache", "写缓存失败 key=$key: ${t.message}")
        }
    }

    fun isFresh(entry: Entry, ttlMs: Long): Boolean {
        if (ttlMs <= 0) return false
        val age = System.currentTimeMillis() - entry.savedAtMs
        return age in 0..ttlMs
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching {
            Files.list(directory).use { stream ->
                stream.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
        Unit
    }

    private fun fileFor(key: String): Path = directory.resolve(sha1(key) + ".json")

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
