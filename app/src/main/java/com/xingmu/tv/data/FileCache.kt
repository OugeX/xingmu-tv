package com.xingmu.tv.data

import com.xingmu.tv.core.AppDispatchers
import com.xingmu.tv.core.TvLog
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 文件级网络缓存（stale-while-revalidate 的落盘层）：
 *  - 网络成功 → 覆盖写（先写 .tmp 再 rename，避免半截文件）；
 *  - 网络失败 → 调用方回退读取旧内容（stale）；
 *  - 目录在 cacheDir 下，系统清理缓存不会影响用户配置。
 */
class FileCache(dir: File) {

    data class Entry(val data: String, val savedAtMs: Long)

    private val directory: File = dir

    init {
        if (!directory.exists()) runCatching { directory.mkdirs() }
    }

    suspend fun read(key: String): Entry? = withContext(AppDispatchers.IoLimited) {
        try {
            val file = fileFor(key)
            if (!file.exists()) return@withContext null
            if (file.length() > MAX_BYTES) {
                runCatching { file.delete() }
                return@withContext null
            }
            Entry(file.readText(Charsets.UTF_8), file.lastModified())
        } catch (t: Throwable) {
            TvLog.w("FileCache", "读缓存失败 key=$key", t)
            null
        }
    }

    suspend fun write(key: String, data: String) = withContext(AppDispatchers.IoLimited) {
        try {
            if (data.length > MAX_BYTES) return@withContext
            if (!directory.exists()) directory.mkdirs()
            val target = fileFor(key)
            val tmp = File(directory, target.name + ".tmp")
            tmp.writeText(data, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                runCatching { target.delete() }
                tmp.renameTo(target)
            }
        } catch (t: Throwable) {
            TvLog.w("FileCache", "写缓存失败 key=$key", t)
        }
    }

    fun isFresh(entry: Entry, ttlMs: Long): Boolean =
        ttlMs > 0 && System.currentTimeMillis() - entry.savedAtMs in 0..ttlMs

    fun clear() {
        runCatching { directory.listFiles()?.forEach { it.delete() } }
    }

    private fun fileFor(key: String): File = File(directory, sha1(key) + ".json")

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private companion object {
        /** 单条缓存上限：首页 JSON 45–164KB，超过 2MB 视为异常。 */
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
