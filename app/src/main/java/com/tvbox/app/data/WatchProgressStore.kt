package com.tvbox.app.data

import android.content.Context
import org.json.JSONObject

/**
 * 追剧进度（继续观看 / 已看标记）。SharedPreferences 存 JSON，量小、首帧后懒加载。
 */
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

class WatchProgressStore(context: Context) {

    private val prefs = context.getSharedPreferences("tvbox_progress", Context.MODE_PRIVATE)

    fun list(): List<WatchProgress> {
        val keys = prefs.getStringSet(KEY_INDEX, emptySet()).orEmpty()
        return keys.mapNotNull { key -> prefs.getString("p.$key", null)?.let(::decode) }
            .sortedByDescending { it.updatedAt }
    }

    fun lastFor(sourceId: String, vodId: String): WatchProgress? =
        prefs.getString("p.${keyOf(sourceId, vodId)}", null)?.let(::decode)

    fun save(progress: WatchProgress) {
        val key = keyOf(progress.sourceId, progress.vodId)
        val index = prefs.getStringSet(KEY_INDEX, emptySet()).orEmpty().toMutableSet()
        index.add(key)
        prefs.edit()
            .putString("p.$key", encode(progress))
            .putStringSet(KEY_INDEX, index)
            .apply()
    }

    fun markWatched(sourceId: String, vodId: String, episodeIndex: Int) {
        val key = "watched.${keyOf(sourceId, vodId)}"
        val current = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        current.add(episodeIndex.toString())
        prefs.edit().putStringSet(key, current).apply()
    }

    fun watchedEpisodes(sourceId: String, vodId: String): Set<Int> =
        prefs.getStringSet("watched.${keyOf(sourceId, vodId)}", emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()

    private fun keyOf(sourceId: String, vodId: String): String = "$sourceId|$vodId"

    private fun encode(progress: WatchProgress): String = JSONObject().apply {
        put("vodId", progress.vodId)
        put("sourceId", progress.sourceId)
        put("name", progress.name)
        put("pic", progress.pic)
        put("playFrom", progress.playFrom)
        put("episodeIndex", progress.episodeIndex)
        put("episodeTitle", progress.episodeTitle)
        put("positionMs", progress.positionMs)
        put("durationMs", progress.durationMs)
        put("updatedAt", progress.updatedAt)
    }.toString()

    private fun decode(text: String): WatchProgress? = runCatching {
        val obj = JSONObject(text)
        WatchProgress(
            vodId = obj.optString("vodId"),
            sourceId = obj.optString("sourceId"),
            name = obj.optString("name"),
            pic = obj.optString("pic"),
            playFrom = obj.optString("playFrom"),
            episodeIndex = obj.optInt("episodeIndex", 1),
            episodeTitle = obj.optString("episodeTitle"),
            positionMs = obj.optLong("positionMs", 0L),
            durationMs = obj.optLong("durationMs", 0L),
            updatedAt = obj.optLong("updatedAt", 0L),
        )
    }.getOrNull()

    private companion object {
        const val KEY_INDEX = "progress_index"
    }
}
