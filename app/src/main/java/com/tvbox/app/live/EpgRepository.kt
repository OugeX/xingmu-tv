package com.tvbox.app.live

import com.tvbox.app.core.TvLog
import com.tvbox.app.player.PlayerHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * EPG 拉取 / 合并 / 落盘（PLAN_SOURCES §3.5.1）。
 *
 * - 一源最多 3 个 XMLTV 地址，单个 ≤8MB，失败静默保留旧数据；
 * - 结果落 `files/epg/epg_cache.txt`（离线可看），进入直播页读缓存 + 30 分钟后台刷新；
 * - 解析全部在 IO 线程，主线程只接收 [EpgIndex]。
 *
 * 缓存格式（TSV，一行一档，避免再引 JSON 依赖）：
 * `channelId \t startMs \t stopMs \t title \t desc`
 */
class EpgRepository(
    private val cacheFile: File,
    private val client: OkHttpClient = PlayerHttp.client.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {

    @Volatile
    private var index: EpgIndex = EpgIndex.EMPTY

    fun current(): EpgIndex = index

    suspend fun loadCache(): EpgIndex = withContext(Dispatchers.IO) {
        if (index.programCount > 0) return@withContext index
        val loaded = try {
            if (!cacheFile.exists()) EpgIndex.EMPTY else parseCache(cacheFile.readText())
        } catch (t: Throwable) {
            TvLog.w(TAG, "epg cache read failed: ${t.message}")
            EpgIndex.EMPTY
        }
        if (loaded.programCount > 0) {
            index = loaded
            TvLog.i(TAG, "epg cache loaded: ${loaded.programCount} programs")
        }
        loaded
    }

    /**
     * 拉取并合并多个 XMLTV 地址。全部失败时返回旧索引（不把界面打空）。
     */
    suspend fun refresh(urls: List<String>, nowMs: Long = System.currentTimeMillis()): EpgIndex =
        withContext(Dispatchers.IO) {
            val targets = urls.map { it.trim() }.filter { it.startsWith("http") }.distinct().take(MAX_URLS)
            if (targets.isEmpty()) return@withContext index

            val merged = LinkedHashMap<String, EpgProgram>()
            var okCount = 0
            for (url in targets) {
                val programs = fetchOne(url, nowMs)
                if (programs == null) continue
                okCount++
                for (program in programs) {
                    val key = "${program.channelId}@${program.startMs}"
                    // 注意：API 21 没有 LinkedHashMap.putIfAbsent（Java 8 默认方法），手动判断
                    if (!merged.containsKey(key)) merged[key] = program
                }
            }
            if (okCount == 0) {
                TvLog.w(TAG, "epg refresh failed for all ${targets.size} urls, keep ${index.programCount}")
                return@withContext index
            }
            val grouped = merged.values
                .sortedBy { it.startMs }
                .groupBy { it.channelId }
            val fresh = EpgIndex(grouped, nowMs)
            index = fresh
            saveCache(merged.values.toList(), nowMs)
            TvLog.i(TAG, "epg refreshed: ${fresh.programCount} programs from $okCount/${targets.size} urls")
            fresh
        }

    private fun fetchOne(url: String, nowMs: Long): List<EpgProgram>? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", PlayerHttp.USER_AGENT)
                .header("Accept", "application/xml,text/xml,*/*")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    TvLog.w(TAG, "epg http ${response.code} $url")
                    return null
                }
                val body = response.body ?: return null
                body.byteStream().use { stream ->
                    // EpgParser 内部按 1f 8b 判 gzip，并限制解压后 8MB
                    EpgParser.parse(stream, nowMs)
                }
            }
        } catch (t: Throwable) {
            TvLog.w(TAG, "epg fetch failed $url: ${t.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // 本地缓存
    // ------------------------------------------------------------------

    private fun saveCache(programs: List<EpgProgram>, updatedAtMs: Long) {
        try {
            val dir = cacheFile.parentFile
            if (dir != null && !dir.exists()) dir.mkdirs()
            val builder = StringBuilder(programs.size * 64)
            builder.append('#').append(CACHE_VERSION).append('\t').append(updatedAtMs).append('\n')
            for (program in programs) {
                builder.append(program.channelId).append('\t')
                    .append(program.startMs).append('\t')
                    .append(program.stopMs).append('\t')
                    .append(clean(program.title)).append('\t')
                    .append(clean(program.desc ?: ""))
                    .append('\n')
            }
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(builder.toString())
            if (cacheFile.exists()) cacheFile.delete()
            tmp.renameTo(cacheFile)
        } catch (t: Throwable) {
            TvLog.w(TAG, "epg cache save failed: ${t.message}")
        }
    }

    private fun parseCache(text: String): EpgIndex {
        val programs = ArrayList<EpgProgram>(512)
        var updatedAt = 0L
        for ((lineIndex, line) in text.lineSequence().withIndex()) {
            if (line.isBlank()) continue
            if (lineIndex == 0 && line.startsWith("#")) {
                val parts = line.split('\t')
                updatedAt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                if (parts.getOrNull(0) != "#$CACHE_VERSION") return EpgIndex.EMPTY
                continue
            }
            val parts = line.split('\t')
            if (parts.size < 4) continue
            val start = parts[1].toLongOrNull() ?: continue
            val stop = parts[2].toLongOrNull() ?: continue
            programs += EpgProgram(
                channelId = parts[0],
                title = parts[3],
                startMs = start,
                stopMs = stop,
                desc = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
            )
        }
        return EpgIndex(programs.sortedBy { it.startMs }.groupBy { it.channelId }, updatedAt)
    }

    private fun clean(value: String): String = value.replace('\t', ' ').replace('\n', ' ').take(120)

    private companion object {
        const val TAG = "Epg"
        const val MAX_URLS = 3
        const val CACHE_VERSION = "tvbox-epg-v1"
    }
}
