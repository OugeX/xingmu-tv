package com.tvbox.desktop.data

import com.tvbox.desktop.core.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * `assets/live_seed.json` 解析（桌面端只读，不修改种子）。
 *
 * 频道线路排序：实测 tier 降序（4K > 1080p > 720p …）→ fallback_only 最后 → priority。
 * 降清晰度播放直接复用这个顺序。
 */
object LiveSeedLoader {

    fun load(paths: AppPaths): LiveSnapshot {
        val (text, source) = paths.readBundled("live_seed.json")
            ?: return LiveSnapshot(emptyList(), emptyList(), null, "缺失", "找不到 live_seed.json（仓库 assets/ 或 jar 资源）")
        return try {
            parse(text, source)
        } catch (t: Throwable) {
            Log.e("LiveSeed", "解析失败", t)
            LiveSnapshot(emptyList(), emptyList(), null, source, "频道数据解析失败：${t.message}")
        }
    }

    fun parse(text: String, source: String = "assets/live_seed.json"): LiveSnapshot {
        return try {
            val trimmed = text.trimStart()
            if (trimmed.startsWith("[")) {
                val channels = parseChannels(JSONArray(trimmed), emptyList())
                buildSnapshot(channels, emptyList(), null, source, channels.size)
            } else {
                val root = JSONObject(trimmed)
                val groups = parseGroups(root)
                val array = root.optJSONArray("channels") ?: root.optJSONArray("lives") ?: JSONArray()
                val channels = parseChannels(array, groups)
                if (channels.isEmpty()) {
                    LiveSnapshot(emptyList(), emptyList(), root.optString("generated_at").ifBlank { null }, source, "频道列表为空")
                } else {
                    buildSnapshot(
                        channels = channels,
                        seedGroups = groups,
                        generatedAt = root.optString("generated_at").ifBlank { null },
                        source = source,
                        _unused = 0,
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w("LiveSeed", "解析失败: ${t.message}")
            LiveSnapshot(emptyList(), emptyList(), null, source, "频道数据解析失败：${t.message}")
        }
    }

    private fun buildSnapshot(
        channels: List<LiveChannel>,
        seedGroups: List<LiveGroup>,
        generatedAt: String?,
        source: String,
        _unused: Int,
    ): LiveSnapshot {
        val groups = LinkedHashMap<String, LiveGroup>()
        seedGroups.sortedBy { it.order }.forEach { groups[it.id] = it.copy(count = 0) }
        var nextOrder = (seedGroups.maxOfOrNull { it.order } ?: 0) + 1
        for (channel in channels) {
            if (!groups.containsKey(channel.groupId)) {
                groups[channel.groupId] = LiveGroup(channel.groupId, channel.group, nextOrder++, 0)
            }
        }
        val groupOrder = groups.keys.withIndex().associate { (index, id) -> id to index }
        val ordered = channels
            .sortedWith(
                compareBy<LiveChannel>(
                    { groupOrder[it.groupId] ?: Int.MAX_VALUE },
                    { it.number },
                    { it.name },
                ),
            )
            .mapIndexed { index, channel -> channel.copy(number = index + 1) }
        val counts = ordered.groupingBy { it.groupId }.eachCount()
        val finalGroups = groups.values.map { it.copy(count = counts[it.id] ?: 0) }
        Log.i("LiveSeed", "loaded ${ordered.size} channels / ${finalGroups.size} groups from $source")
        return LiveSnapshot(finalGroups, ordered, generatedAt, source)
    }

    private fun parseGroups(root: JSONObject): List<LiveGroup> {
        val array = root.optJSONArray("groups") ?: return emptyList()
        val result = ArrayList<LiveGroup>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").ifBlank { obj.optString("title") }
            if (name.isBlank()) continue
            val id = obj.optString("id").ifBlank { name }
            result += LiveGroup(id, name, obj.optInt("order", i + 1), 0)
        }
        return result
    }

    private fun parseChannels(array: JSONArray, seedGroups: List<LiveGroup>): List<LiveChannel> {
        val idByName = seedGroups.associate { it.name to it.id }
        val nameById = seedGroups.associate { it.id to it.name }
        val usedIds = HashSet<String>()
        val result = ArrayList<LiveChannel>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").ifBlank { obj.optString("title") }.trim()
            if (name.isBlank()) continue
            val rawGroupName = obj.optString("group").ifBlank { obj.optString("category") }.trim()
            val groupId = obj.optString("group_id").ifBlank { idByName[rawGroupName] ?: rawGroupName.ifBlank { "other" } }
            val groupName = nameById[groupId] ?: rawGroupName.ifBlank { groupId }

            var id = obj.optString("id").ifBlank { obj.optString("tvg_id") }.ifBlank { "ch${i + 1}" }
            if (!usedIds.add(id)) {
                var suffix = 2
                while (!usedIds.add("$id-$suffix")) suffix++
                id = "$id-$suffix"
            }

            val lines = parseLines(obj)
            result += LiveChannel(
                id = id,
                name = name,
                group = groupName,
                groupId = groupId,
                number = obj.optInt("order", i + 1),
                tvgId = obj.optString("tvg_id").ifBlank { null },
                logo = obj.optString("logo").ifBlank { null },
                desc = obj.optString("desc").ifBlank { null },
                status = obj.optString("status").ifBlank { "unknown" },
                quality = obj.optString("quality").takeIf { it.isNotBlank() && it != "未知" },
                lines = lines,
            )
        }
        return result
    }

    private fun parseLines(obj: JSONObject): List<LiveLine> {
        data class Raw(
            val url: String,
            val priority: Int,
            val quality: String?,
            val tier: String?,
            val fallbackOnly: Boolean,
            val latencyMs: Int?,
            val height: Int,
        )

        val raw = ArrayList<Raw>()
        obj.optJSONArray("sources")?.let { sources ->
            for (i in 0 until sources.length()) {
                when (val item = sources.opt(i)) {
                    is JSONObject -> raw += Raw(
                        url = item.optString("url"),
                        priority = item.optInt("priority", i + 1),
                        quality = item.optString("quality").takeIf { it.isNotBlank() && it != "未知" },
                        tier = item.optString("tier").takeIf { it.isNotBlank() },
                        fallbackOnly = item.optBoolean("fallback_only", false),
                        latencyMs = item.optInt("ttfb_ms", -1).takeIf { it >= 0 },
                        height = item.optInt("measured_height", 0),
                    )
                    is String -> if (item.isNotBlank()) raw += Raw(item, i + 1, null, null, false, null, 0)
                }
            }
        }
        if (raw.isEmpty()) {
            obj.optJSONArray("urls")?.let { urls ->
                for (i in 0 until urls.length()) {
                    val u = urls.optString(i)
                    if (u.isNotBlank()) raw += Raw(u, i + 1, null, null, false, null, 0)
                }
            }
        }
        if (raw.isEmpty()) {
            val single = obj.optString("url")
            if (single.isNotBlank()) raw += Raw(single, 1, null, null, false, null, 0)
        }

        val dedup = LinkedHashMap<String, Raw>()
        for (item in raw) {
            if (!item.url.startsWith("http://", true) && !item.url.startsWith("https://", true)) continue
            val existing = dedup[item.url]
            if (existing == null || existing.priority > item.priority) dedup[item.url] = item
        }
        return dedup.values.sortedWith(
            compareBy<Raw>(
                { if (it.fallbackOnly) 1 else 0 },
                { -rankOf(it.tier, it.quality, it.height) },
                { it.priority },
            ),
        ).map { item ->
            LiveLine(
                url = item.url,
                quality = item.quality ?: qualityFromTier(item.tier),
                tier = item.tier,
                fallbackOnly = item.fallbackOnly,
                latencyMs = item.latencyMs,
            )
        }
    }

    private fun rankOf(tier: String?, quality: String?, height: Int): Int {
        val t = (tier ?: quality ?: "").lowercase()
        return when {
            t.startsWith("4k") || t.startsWith("2160") -> 6
            t.startsWith("1080") -> 5
            t.startsWith("720") -> 4
            t.startsWith("576") -> 3
            t.startsWith("480") -> 2
            t.startsWith("360") || t.startsWith("≤360") || t == "unknown" -> 1
            t == "error" || t.contains("无视频轨") -> -1
            height >= 2000 -> 6
            height >= 1000 -> 5
            height >= 700 -> 4
            height >= 500 -> 3
            height >= 400 -> 2
            else -> 1
        }
    }

    private fun qualityFromTier(tier: String?): String? = when (tier?.lowercase()) {
        "4k" -> "2160p"
        "1080p", "720p", "576p", "480p", "360p" -> tier
        else -> null
    }
}
