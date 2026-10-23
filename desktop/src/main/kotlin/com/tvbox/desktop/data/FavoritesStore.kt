package com.tvbox.desktop.data

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Path

data class VodFavorite(
    val sourceId: String,
    val vodId: String,
    val name: String,
    val pic: String,
    val updatedAt: Long,
)

data class LiveFavorite(
    val channelId: String,
    val name: String,
    val group: String,
    val updatedAt: Long,
)

/** 收藏（favorites.json）：点播按 sourceId+vodId 去重，直播按 channel id 去重。 */
class FavoritesStore(
    private val file: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun vodList(): List<VodFavorite> =
        load().vod.sortedByDescending { it.updatedAt }

    fun liveList(): List<LiveFavorite> =
        load().live.sortedByDescending { it.updatedAt }

    fun isVodFavorite(sourceId: String, vodId: String): Boolean =
        load().vod.any { it.sourceId == sourceId && it.vodId == vodId }

    fun isLiveFavorite(channelId: String): Boolean =
        load().live.any { it.channelId == channelId }

    fun toggleVod(sourceId: String, vodId: String, name: String, pic: String): Boolean {
        val snapshot = load()
        val existing = snapshot.vod.firstOrNull { it.sourceId == sourceId && it.vodId == vodId }
        return if (existing != null) {
            snapshot.vod.removeAll { it.sourceId == sourceId && it.vodId == vodId }
            write(snapshot)
            false
        } else {
            snapshot.vod.add(VodFavorite(sourceId, vodId, name, pic, clock()))
            write(snapshot)
            true
        }
    }

    /** 幂等添加（同一作品重复添加只保留一条并刷新时间）。 */
    fun addVod(sourceId: String, vodId: String, name: String, pic: String) {
        val snapshot = load()
        snapshot.vod.removeAll { it.sourceId == sourceId && it.vodId == vodId }
        snapshot.vod.add(VodFavorite(sourceId, vodId, name, pic, clock()))
        write(snapshot)
    }

    fun toggleLive(channelId: String, name: String, group: String): Boolean {
        val snapshot = load()
        val existing = snapshot.live.firstOrNull { it.channelId == channelId }
        return if (existing != null) {
            snapshot.live.removeAll { it.channelId == channelId }
            write(snapshot)
            false
        } else {
            snapshot.live.add(LiveFavorite(channelId, name, group, clock()))
            write(snapshot)
            true
        }
    }

    fun addLive(channelId: String, name: String, group: String) {
        val snapshot = load()
        snapshot.live.removeAll { it.channelId == channelId }
        snapshot.live.add(LiveFavorite(channelId, name, group, clock()))
        write(snapshot)
    }

    fun removeVod(sourceId: String, vodId: String) {
        val snapshot = load()
        snapshot.vod.removeAll { it.sourceId == sourceId && it.vodId == vodId }
        write(snapshot)
    }

    fun removeLive(channelId: String) {
        val snapshot = load()
        snapshot.live.removeAll { it.channelId == channelId }
        write(snapshot)
    }

    private data class Snapshot(
        val vod: MutableList<VodFavorite>,
        val live: MutableList<LiveFavorite>,
    )

    private fun load(): Snapshot {
        val json = JsonStorage.read(file) ?: return Snapshot(mutableListOf(), mutableListOf())
        val vod = mutableListOf<VodFavorite>()
        json.optJSONArray("vod")?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val sourceId = obj.optString("sourceId")
                val vodId = obj.optString("vodId")
                if (sourceId.isBlank() || vodId.isBlank()) continue
                vod.add(
                    VodFavorite(
                        sourceId = sourceId,
                        vodId = vodId,
                        name = obj.optString("name"),
                        pic = obj.optString("pic"),
                        updatedAt = obj.optLong("updatedAt", 0L),
                    ),
                )
            }
        }
        val live = mutableListOf<LiveFavorite>()
        json.optJSONArray("live")?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val channelId = obj.optString("channelId")
                if (channelId.isBlank()) continue
                live.add(
                    LiveFavorite(
                        channelId = channelId,
                        name = obj.optString("name"),
                        group = obj.optString("group"),
                        updatedAt = obj.optLong("updatedAt", 0L),
                    ),
                )
            }
        }
        return Snapshot(dedupeVod(vod), dedupeLive(live))
    }

    private fun dedupeVod(items: List<VodFavorite>): MutableList<VodFavorite> {
        val byKey = LinkedHashMap<String, VodFavorite>()
        items.forEach { item ->
            val key = item.sourceId + "|" + item.vodId
            val existing = byKey[key]
            if (existing == null || item.updatedAt >= existing.updatedAt) byKey[key] = item
        }
        return byKey.values.toMutableList()
    }

    private fun dedupeLive(items: List<LiveFavorite>): MutableList<LiveFavorite> {
        val byId = LinkedHashMap<String, LiveFavorite>()
        items.forEach { item ->
            val existing = byId[item.channelId]
            if (existing == null || item.updatedAt >= existing.updatedAt) byId[item.channelId] = item
        }
        return byId.values.toMutableList()
    }

    private fun write(snapshot: Snapshot) {
        val json = JSONObject()
            .put("version", 1)
            .put("vod", JSONArray().also { array ->
                snapshot.vod.forEach { item ->
                    array.put(
                        JSONObject()
                            .put("sourceId", item.sourceId)
                            .put("vodId", item.vodId)
                            .put("name", item.name)
                            .put("pic", item.pic)
                            .put("updatedAt", item.updatedAt),
                    )
                }
            })
            .put("live", JSONArray().also { array ->
                snapshot.live.forEach { item ->
                    array.put(
                        JSONObject()
                            .put("channelId", item.channelId)
                            .put("name", item.name)
                            .put("group", item.group)
                            .put("updatedAt", item.updatedAt),
                    )
                }
            })
        JsonStorage.write(file, json)
    }
}
