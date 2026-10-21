package com.tvbox.app.data

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 预置详情包（t26 产出）的解析与校验（t27）。
 *
 * 包结构（release/detail-seed-v1.zip）：
 *   manifest.json  {"version":"v1","source":"bfzy1","count":500,
 *                   "detail_seed_bytes":2885658,"detail_seed_sha256":"e9fc0ab8...","sample_count":20}
 *   detail_seed.json {"schema_version":1,"source_id":"bfzy1","count":500,
 *                      "entries":[{"vodId","sourceId","name","year","fetchedAt","collectedFrom","detailJson"}]}
 *
 * detailJson 是「单条 MacCMS item」，不是 {code,list} 信封；落库时只存这段字符串，
 * 命中时由 [MacCmsParser] 包一层 list 再解析，和在线接口走同一条解析路径。
 */
data class SeedPackageEntry(
    val vodId: String,
    val sourceId: String,
    val name: String,
    val year: String,
    val fetchedAtMs: Long,
    val detailJson: String,
)

data class SeedPackage(
    val schemaVersion: Int,
    val sourceId: String,
    val count: Int,
    val entries: List<SeedPackageEntry>,
) {
    fun toSeedEntries(): List<SeedEntry> = entries.map {
        SeedEntry(
            vodId = it.vodId,
            sourceId = it.sourceId,
            name = it.name,
            year = it.year,
            fetchedAtMs = it.fetchedAtMs,
            detailJson = it.detailJson,
        )
    }

    companion object {
        /** 解析 detail_seed.json；单条损坏不影响整包（跳过并计数）。 */
        fun parse(json: String, nowMs: Long = System.currentTimeMillis()): SeedPackage {
            val root = JSONObject(json)
            val array = root.optJSONArray("entries") ?: throw IllegalArgumentException("缺少 entries")
            val entries = ArrayList<SeedPackageEntry>(array.length())
            val fallbackSource = root.optString("source_id")
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val vodId = obj.optString("vodId").trim()
                val detail = obj.optJSONObject("detailJson")?.toString() ?: ""
                if (vodId.isEmpty() || detail.isEmpty()) continue
                entries += SeedPackageEntry(
                    vodId = vodId,
                    sourceId = obj.optString("sourceId").ifBlank { fallbackSource },
                    name = obj.optString("name"),
                    year = obj.optString("year"),
                    fetchedAtMs = SeedTime.parseIsoMs(obj.optString("fetchedAt")) ?: nowMs,
                    detailJson = detail,
                )
            }
            return SeedPackage(
                schemaVersion = root.optInt("schema_version", 1),
                sourceId = fallbackSource,
                count = entries.size,
                entries = entries,
            )
        }
    }
}

/** manifest.json（zip 内），用于下载后校验。 */
data class SeedManifest(
    val version: String,
    val sourceId: String,
    val count: Int,
    val detailSeedBytes: Long,
    val detailSeedSha256: String,
) {
    companion object {
        fun parse(json: String): SeedManifest {
            val root = JSONObject(json)
            return SeedManifest(
                version = root.optString("version", "v1"),
                sourceId = root.optString("source"),
                count = root.optInt("count", 0),
                detailSeedBytes = root.optLong("detail_seed_bytes", -1L),
                detailSeedSha256 = root.optString("detail_seed_sha256").lowercase(),
            )
        }
    }
}

/** zip 内容（只关心两个文件）。 */
data class SeedZipContent(
    val manifestJson: String,
    val seedJson: String,
) {
    fun manifest(): SeedManifest = SeedManifest.parse(manifestJson)

    fun seedPackage(): SeedPackage = SeedPackage.parse(seedJson)
}

object SeedPackageFiles {

    const val MANIFEST_NAME = "manifest.json"
    const val SEED_NAME = "detail_seed.json"

    /**
     * 读 zip 内存内容。包只有 378KB（解压 2.75MB），整包读进内存比落盘再读更省 IO，
     * 低端盒子（eMMC 慢）导入耗时主要花在 SQLite 写入而不是这里。
     */
    fun readZip(file: File): SeedZipContent {
        var manifest: String? = null
        var seed: String? = null
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                val bytes = zip.readBytes()
                when (name) {
                    MANIFEST_NAME -> manifest = bytes.toString(Charsets.UTF_8)
                    SEED_NAME -> seed = bytes.toString(Charsets.UTF_8)
                }
            }
        }
        return SeedZipContent(
            manifestJson = manifest ?: throw IllegalArgumentException("zip 缺少 $MANIFEST_NAME"),
            seedJson = seed ?: throw IllegalArgumentException("zip 缺少 $SEED_NAME"),
        )
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) out.append(String.format("%02x", b))
        return out.toString()
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /**
     * manifest 里记的是完整 sha256；下载校验用前缀（默认 16 位）即可发现截断/串包，
     * 与 tools/verify_detail_seed.py 的 sha[:16] 输出一致。
     */
    fun sha256PrefixOk(actual: String, expected: String, prefixLength: Int = 16): Boolean {
        if (expected.length < prefixLength) return false
        return actual.lowercase().startsWith(expected.lowercase().take(prefixLength))
    }

    /** 完整校验：字节数 + sha256 前缀。 */
    fun verify(manifest: SeedManifest, seedJson: String): Boolean {
        val bytes = seedJson.toByteArray(Charsets.UTF_8)
        if (manifest.detailSeedBytes > 0 && bytes.size.toLong() != manifest.detailSeedBytes) return false
        if (manifest.detailSeedSha256.isBlank()) return false
        return sha256PrefixOk(sha256Hex(bytes), manifest.detailSeedSha256)
    }
}

/** ISO-8601（2026-10-22T05:51:57+08:00）→ epoch ms；不引入 java.time（minSdk 21 无 desugar）。 */
object SeedTime {

    private val pattern = Regex(
        """(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?""",
    )

    fun parseIsoMs(text: String): Long? {
        val m = pattern.find(text.trim()) ?: return null
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        val hour = m.groupValues[4].toInt()
        val minute = m.groupValues[5].toInt()
        val second = m.groupValues[6].toInt()
        val zone = m.groupValues[7]
        val offsetMinutes = when {
            zone.isEmpty() || zone == "Z" -> 0
            else -> {
                val sign = if (zone.startsWith("-")) -1 else 1
                val digits = zone.drop(1).replace(":", "")
                val hours = digits.take(2).toIntOrNull() ?: 0
                val minutes = digits.drop(2).take(2).toIntOrNull() ?: 0
                sign * (hours * 60 + minutes)
            }
        }
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        return calendar.timeInMillis - offsetMinutes * 60_000L
    }
}
