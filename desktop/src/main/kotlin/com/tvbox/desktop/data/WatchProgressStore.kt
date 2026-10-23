package com.tvbox.desktop.data

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Path

/** 追剧进度（字段与 Android 端 WatchProgress 保持一致）。 */
data class WatchProgress(
    val vodId: String,
    val sourceId: String,
    val name: String,
    val pic: String,
    val playFrom: String,
    val episodeIndex: Int,
    val episodeTitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

data class ProgressSaveOutcome(
    val stored: WatchProgress?,
    val skipped: Boolean,
    val markedWatched: Boolean,
    val advancedEpisode: Boolean,
)

/**
 * 桌面端播放进度存储（progress.json）：
 *  - 一个作品（sourceId+vodId）只保留最近一条；
 *  - 进度 <60s 且 <5% 不记；
 *  - ≥95% 记为该集已看完，episodeIndex 前进到下集（position 归零）；
 *  - 已看集合单独持久化，供选集 ✓ 标记。
 *
 * 语义参考 app/src/main/java/com/xingmu/tv/data/WatchProgressStore.kt，但不依赖 Android/SharedPreferences。
 */
class WatchProgressStore(
    private val file: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun list(): List<WatchProgress> =
        load().items.sortedByDescending { it.updatedAt }

    fun listRecent(limit: Int = 8): List<WatchProgress> =
        if (limit <= 0) emptyList() else list().take(limit)

    fun lastFor(sourceId: String, vodId: String): WatchProgress? =
        load().items.firstOrNull { it.sourceId == sourceId && it.vodId == vodId }

    fun save(progress: WatchProgress, nextEpisodeTitle: String? = null): ProgressSaveOutcome {
        if (!shouldRecord(progress.positionMs, progress.durationMs)) {
            return ProgressSaveOutcome(null, skipped = true, markedWatched = false, advancedEpisode = false)
        }
        val snapshot = load()
        val key = keyOf(progress.sourceId, progress.vodId)
        val timestamp = if (progress.updatedAt > 0) progress.updatedAt else clock()
        var stored = progress.copy(updatedAt = timestamp)
        var marked = false
        var advanced = false

        if (isComplete(progress.positionMs, progress.durationMs)) {
            snapshot.watched.getOrPut(key) { mutableSetOf() }.add(progress.episodeIndex)
            marked = true
            stored = stored.copy(
                episodeIndex = progress.episodeIndex + 1,
                episodeTitle = nextEpisodeTitle.orEmpty(),
                positionMs = 0L,
                durationMs = 0L,
            )
            advanced = true
        }

        snapshot.items.removeAll { it.sourceId == progress.sourceId && it.vodId == progress.vodId }
        snapshot.items.add(stored)
        write(snapshot)
        return ProgressSaveOutcome(stored, skipped = false, markedWatched = marked, advancedEpisode = advanced)
    }

    fun markWatched(sourceId: String, vodId: String, episodeIndex: Int) {
        val snapshot = load()
        snapshot.watched.getOrPut(keyOf(sourceId, vodId)) { mutableSetOf() }.add(episodeIndex)
        write(snapshot)
    }

    fun watchedEpisodes(sourceId: String, vodId: String): Set<Int> =
        load().watched[keyOf(sourceId, vodId)]?.toSet().orEmpty()

    fun remove(sourceId: String, vodId: String) {
        val snapshot = load()
        snapshot.items.removeAll { it.sourceId == sourceId && it.vodId == vodId }
        write(snapshot)
    }

    fun clear() {
        write(Snapshot(mutableListOf(), mutableMapOf()))
    }

    private data class Snapshot(
        val items: MutableList<WatchProgress>,
        val watched: MutableMap<String, MutableSet<Int>>,
    )

    private fun load(): Snapshot {
        val json = JsonStorage.read(file) ?: return Snapshot(mutableListOf(), mutableMapOf())
        val items = mutableListOf<WatchProgress>()
        json.optJSONArray("items")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { decode(it)?.let(items::add) }
            }
        }
        val watched = mutableMapOf<String, MutableSet<Int>>()
        json.optJSONObject("watched")?.let { map ->
            map.keys().asSequence().forEach { key ->
                val set = mutableSetOf<Int>()
                map.optJSONArray(key)?.let { array ->
                    for (i in 0 until array.length()) set.add(array.optInt(i))
                }
                if (set.isNotEmpty()) watched[key] = set
            }
        }
        return Snapshot(items, watched)
    }

    private fun write(snapshot: Snapshot) {
        val json = JSONObject()
            .put("version", 1)
            .put("items", JSONArray().also { array -> snapshot.items.forEach { array.put(encode(it)) } })
            .put("watched", JSONObject().also { map ->
                snapshot.watched.forEach { (key, episodes) ->
                    map.put(key, JSONArray().also { array -> episodes.sorted().forEach(array::put) })
                }
            })
        JsonStorage.write(file, json)
    }

    private fun encode(progress: WatchProgress): JSONObject = JSONObject()
        .put("vodId", progress.vodId)
        .put("sourceId", progress.sourceId)
        .put("name", progress.name)
        .put("pic", progress.pic)
        .put("playFrom", progress.playFrom)
        .put("episodeIndex", progress.episodeIndex)
        .put("episodeTitle", progress.episodeTitle)
        .put("positionMs", progress.positionMs)
        .put("durationMs", progress.durationMs)
        .put("updatedAt", progress.updatedAt)

    private fun decode(obj: JSONObject): WatchProgress? {
        val sourceId = obj.optString("sourceId")
        val vodId = obj.optString("vodId")
        if (sourceId.isBlank() || vodId.isBlank()) return null
        return WatchProgress(
            vodId = vodId,
            sourceId = sourceId,
            name = obj.optString("name"),
            pic = obj.optString("pic"),
            playFrom = obj.optString("playFrom"),
            episodeIndex = obj.optInt("episodeIndex", 1).coerceAtLeast(1),
            episodeTitle = obj.optString("episodeTitle"),
            positionMs = obj.optLong("positionMs", 0L).coerceAtLeast(0L),
            durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L),
            updatedAt = obj.optLong("updatedAt", 0L),
        )
    }

    companion object {
        const val MIN_POSITION_MS = 60_000L
        const val MIN_RATIO = 0.05
        const val COMPLETE_RATIO = 0.95

        fun keyOf(sourceId: String, vodId: String): String = "$sourceId|$vodId"

        /** 进度 <60s 且 <5% 不记（避免刚点开就续播）。 */
        fun shouldRecord(positionMs: Long, durationMs: Long): Boolean {
            if (positionMs <= 0L) return false
            if (positionMs >= MIN_POSITION_MS) return true
            if (durationMs <= 0L) return false
            return positionMs.toDouble() / durationMs.toDouble() >= MIN_RATIO
        }

        fun isComplete(positionMs: Long, durationMs: Long): Boolean {
            if (durationMs <= 0L || positionMs <= 0L) return false
            return positionMs.toDouble() / durationMs.toDouble() >= COMPLETE_RATIO
        }
    }
}
