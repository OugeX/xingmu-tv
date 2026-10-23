package com.tvbox.desktop.data

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Path

data class RecentLive(
    val channelId: String,
    val name: String,
    val group: String,
    val updatedAt: Long,
)

/** 直播最近观看（recent.json）：按 channel id 去重、时间倒序、最多 20 条。 */
class RecentStore(
    private val file: Path,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxItems: Int = MAX_ITEMS,
) {
    fun list(): List<RecentLive> =
        load().sortedByDescending { it.updatedAt }

    fun record(channelId: String, name: String, group: String) {
        if (channelId.isBlank()) return
        val items = load().toMutableList()
        items.removeAll { it.channelId == channelId }
        items.add(0, RecentLive(channelId, name, group, clock()))
        write(items.take(maxItems))
    }

    fun remove(channelId: String) {
        val items = load().toMutableList()
        items.removeAll { it.channelId == channelId }
        write(items)
    }

    fun clear() = write(emptyList())

    private fun load(): List<RecentLive> {
        val json = JsonStorage.read(file) ?: return emptyList()
        val array = json.optJSONArray("items") ?: return emptyList()
        val result = ArrayList<RecentLive>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("channelId")
            if (id.isBlank()) continue
            result.add(
                RecentLive(
                    channelId = id,
                    name = obj.optString("name"),
                    group = obj.optString("group"),
                    updatedAt = obj.optLong("updatedAt", 0L),
                ),
            )
        }
        return result
    }

    private fun write(items: List<RecentLive>) {
        val json = JSONObject()
            .put("version", 1)
            .put("items", JSONArray().also { array ->
                items.forEach { item ->
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

    companion object {
        const val MAX_ITEMS = 20
    }
}
