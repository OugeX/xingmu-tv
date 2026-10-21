package com.tvbox.app.live

import android.content.Context
import com.tvbox.app.core.TvLog
import com.tvbox.app.data.Channel
import com.tvbox.app.data.ChannelUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * `assets/live_seed.json` 读取与兼容解析（t2 产出，见 PLAN_LIVE §5 / PLAN_SOURCES §9.6）。
 *
 * 解析目标：`groups[] + channels[]`，频道对象映射为冻结的 [Channel] + 直播层补充字段
 * （sort order / 数字台号 / 健康度 / 线路 priority）。
 *
 * 兼容性（t2 文件尚未落地时仍可编译运行）：
 * - 文件不存在时回退 `live_seed.example.json`；
 * - 都缺失时返回空快照 + 明确 note「等待 t2 产出」，不崩溃；
 * - 字段别名 `name|title`、`group|group-name|category`、`url|play_url|stream`、`logo|icon`、
 *   `tvg_id|tvg-id`；根为纯数组、或旧版 `lives[]` 也能解析。
 *
 * 解析量级：266 频道 / 406KB，在 `Dispatchers.IO` 一次完成并缓存，主线程零 JSON。
 */
class LiveSeedRepository(
    private val context: Context,
    private val assetName: String = "live_seed.json",
) {

    data class Snapshot(
        val groups: List<LiveGroup>,
        val channels: List<LiveChannel>,
        val source: String,
        val generatedAt: String? = null,
        val note: String? = null,
    )

    @Volatile
    private var cached: Snapshot? = null

    suspend fun load(): Snapshot = withContext(Dispatchers.IO) {
        cached ?: run {
            val (text, source) = readAsset()
            val snapshot = if (text == null) {
                Snapshot(
                    groups = emptyList(),
                    channels = emptyList(),
                    source = "assets/$assetName",
                    note = "未找到内置频道数据：等待 t2 产出 assets/live_seed.json",
                )
            } else {
                val base = parse(text, source)
                val hdKeys = readHdKeys()
                if (base.channels.isEmpty()) {
                    base
                } else {
                    val marked = base.channels.map { channel ->
                        val inHd = if (hdKeys.isNotEmpty()) {
                            channelKey(channel.name, channel.groupName) in hdKeys
                        } else {
                            channel.bestHeight >= 1080 // live_hd.json 缺失时按实测高度兜底
                        }
                        if (inHd) channel.copy(hdZone = true) else channel
                    }
                    base.copy(channels = marked)
                }
            }
            cached = snapshot
            snapshot
        }
    }

    private fun readHdKeys(): Set<String> = try {
        val text = context.assets.open("live_hd.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONObject(text).optJSONArray("channels") ?: JSONArray()
        val keys = HashSet<String>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").trim()
            if (name.isEmpty()) continue
            keys += channelKey(name, obj.optString("group", "").trim())
        }
        TvLog.i(TAG, "live_hd.json loaded: ${keys.size} HD channels")
        keys
    } catch (t: Throwable) {
        TvLog.w(TAG, "live_hd.json unavailable: ${t.message}")
        emptySet()
    }

    private fun channelKey(name: String, group: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), "") + "|" + group.trim()

    fun peek(): Snapshot? = cached

    private fun readAsset(): Pair<String?, String> {
        val candidates = listOf(assetName, "live_seed.example.json", "live.example.json")
        for (name in candidates) {
            try {
                val text = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (text.isNotBlank()) {
                    TvLog.i(TAG, "seed loaded from assets/$name (${text.length} chars)")
                    return text to "assets/$name"
                }
            } catch (t: Throwable) {
                TvLog.w(TAG, "asset $name unavailable: ${t.message}")
            }
        }
        return null to "assets/$assetName"
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    fun parse(text: String, source: String = "assets/$assetName"): Snapshot {
        return try {
            val trimmed = text.trimStart()
            val (groupDefs, channels) = if (trimmed.startsWith("[")) {
                emptyList<LiveGroup>() to parseChannels(JSONArray(trimmed), null, source)
            } else {
                val root = JSONObject(trimmed)
                val groups = parseGroups(root)
                val array = root.optJSONArray("channels")
                    ?: root.optJSONArray("lives")
                    ?: root.optJSONArray("list")
                    ?: JSONArray()
                groups to parseChannels(array, groups, source)
            }
            val sortedGroups = normalizeGroups(groupDefs, channels)
            val ordered = orderChannels(sortedGroups, channels)
            Snapshot(
                groups = sortedGroups.map { g ->
                    g.copy(channelCount = ordered.count { it.groupId == g.id })
                },
                channels = ordered,
                source = source,
                generatedAt = readGeneratedAt(text),
            )
        } catch (t: Throwable) {
            TvLog.e(TAG, "seed parse failed: ${t.message}", t)
            Snapshot(emptyList(), emptyList(), source, note = "频道数据解析失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun readGeneratedAt(text: String): String? = try {
        JSONObject(text).optString("generated_at", "").ifBlank { null }
    } catch (t: Throwable) {
        null
    }

    private fun parseGroups(root: JSONObject): List<LiveGroup> {
        val array = root.optJSONArray("groups") ?: return emptyList()
        val result = ArrayList<LiveGroup>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = pick(obj, "name", "title", "group-name") ?: continue
            val id = pick(obj, "id", "group_id") ?: name
            result += LiveGroup(
                id = id,
                name = name,
                order = obj.optInt("order", i + 1),
            )
        }
        return result
    }

    private fun parseChannels(
        array: JSONArray,
        seedGroups: List<LiveGroup>?,
        source: String,
    ): List<LiveChannel> {
        val idByName = seedGroups.orEmpty().associate { it.name to it.id }
        val nameById = seedGroups.orEmpty().associate { it.id to it.name }
        val usedIds = HashSet<String>()
        val result = ArrayList<LiveChannel>(array.length())

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = pick(obj, "name", "title", "tvg-name") ?: continue
            val rawGroupName = pick(obj, "group", "group-name", "groupName", "category").orEmpty()
            val groupId = pick(obj, "group_id", "groupId")
                ?: idByName[rawGroupName]
                ?: rawGroupName.ifBlank { "other" }
            val groupName = nameById[groupId] ?: rawGroupName.ifBlank { groupId }

            var id = pick(obj, "id", "tvg_id", "tvg-id", "tvgId") ?: slug(name, i)
            if (!usedIds.add(id)) {
                var suffix = 2
                while (!usedIds.add("$id-$suffix")) suffix++
                id = "$id-$suffix"
            }

            val lines = parseLines(obj)
            val channel = Channel(
                id = id,
                name = name,
                group = groupName,
                logo = pick(obj, "logo", "icon", "tvg-logo"),
                tvgId = pick(obj, "tvg_id", "tvg-id", "tvgId") ?: id,
                desc = pick(obj, "desc", "description"),
                urls = lines.map {
                    ChannelUrl(
                        url = it.url,
                        quality = it.quality,
                        resolution = it.resolution,
                        latencyMs = it.latencyMs,
                        fallbackOnly = it.fallbackOnly,
                    )
                },
            )
            result += LiveChannel(
                channel = channel,
                groupId = groupId,
                groupName = groupName,
                order = obj.optInt("order", i + 1),
                number = 0,
                health = LiveHealth.from(pick(obj, "status", "health")),
                quality = pick(obj, "quality")?.takeIf { it != "未知" },
                sourceCount = obj.optInt("source_count", lines.size),
                latencyMs = obj.optInt("latency_ms", -1).takeIf { it >= 0 },
                lines = lines,
                bestTier = pick(obj, "best_tier"),
                bestHeight = obj.optInt("best_height", 0),
            )
        }
        TvLog.i(TAG, "parsed ${result.size} channels from $source")
        return result
    }

    /** sources[]（含 priority/fallback_only）优先；否则 urls[]；否则单 url 字段。 */
    private fun parseLines(obj: JSONObject): List<LiveLine> {
        val raw = ArrayList<RawLine>()

        val sources = obj.optJSONArray("sources")
        if (sources != null) {
            for (i in 0 until sources.length()) {
                when (val item = sources.opt(i)) {
                    is JSONObject -> raw += rawLine(item, i)
                    is String -> if (item.isNotBlank()) raw += RawLine(item, priority = i + 1)
                }
            }
        }
        if (raw.isEmpty()) {
            val urls = obj.optJSONArray("urls")
            if (urls != null) {
                for (i in 0 until urls.length()) {
                    when (val item = urls.opt(i)) {
                        is JSONObject -> raw += rawLine(item, i)
                        is String -> if (item.isNotBlank()) raw += RawLine(item, priority = i + 1)
                    }
                }
            }
        }
        if (raw.isEmpty()) {
            pick(obj, "url", "play_url", "stream")?.let { raw += RawLine(it, priority = 1) }
        }

        val dedup = LinkedHashMap<String, RawLine>()
        for (item in raw) {
            if (item.url.isBlank()) continue
            val existing = dedup[item.url]
            if (existing == null || existing.priority > item.priority) dedup[item.url] = item
        }
        // 实测 tier 降序（4K > 1080p > ... > error），fallback_only 永远最后，再按 priority
        val ordered = dedup.values.sortedWith(
            compareBy<RawLine>(
                { if (it.fallbackOnly) 1 else 0 },
                { -rankOf(it) },
                { it.priority },
            ),
        )
        return ordered.mapIndexed { index, item ->
            LiveLine(
                index = index,
                url = item.url,
                quality = item.quality,
                resolution = item.resolution,
                latencyMs = item.latencyMs,
                fallbackOnly = item.fallbackOnly,
                tier = item.tier,
                measuredWidth = item.measuredWidth,
                measuredHeight = item.measuredHeight,
                measuredCodec = item.measuredCodec,
                measuredFps = item.measuredFps,
                bitrateBps = item.bitrateBps,
            )
        }
    }

    private fun rankOf(line: RawLine): Int = when (line.tier?.lowercase()) {
        "4k", "2160p" -> 6
        "1080p" -> 5
        "720p" -> 4
        "576p" -> 3
        "480p" -> 2
        "≤360p", "≤360", "360p", "unknown" -> 1
        "error", "无视频轨", "novideo" -> -1
        else -> if (line.measuredHeight > 0) 2 else 1
    }

    private fun rawLine(obj: JSONObject, index: Int): RawLine {
        val url = pick(obj, "url", "play_url", "stream").orEmpty()
        return RawLine(
            url = url,
            priority = obj.optInt("priority", index + 1),
            quality = pick(obj, "quality"),
            resolution = pick(obj, "resolution"),
            latencyMs = obj.optInt("ttfb_ms", -1).takeIf { it >= 0 },
            fallbackOnly = obj.optBoolean("fallback_only", false),
            tier = pick(obj, "tier"),
            measuredWidth = obj.optInt("measured_width", 0),
            measuredHeight = obj.optInt("measured_height", 0),
            measuredCodec = pick(obj, "measured_codec"),
            measuredFps = obj.optDouble("measured_fps", 0.0).toFloat(),
            bitrateBps = obj.optInt("bitrate_bps", 0),
        )
    }

    private data class RawLine(
        val url: String,
        val priority: Int = 1,
        val quality: String? = null,
        val resolution: String? = null,
        val latencyMs: Int? = null,
        val fallbackOnly: Boolean = false,
        val tier: String? = null,
        val measuredWidth: Int = 0,
        val measuredHeight: Int = 0,
        val measuredCodec: String? = null,
        val measuredFps: Float = 0f,
        val bitrateBps: Int = 0,
    )

    // ------------------------------------------------------------------
    // 排序 / 分组
    // ------------------------------------------------------------------

    /** 种子里没声明的分组补在末尾，保证“其他”类频道也能显示。 */
    private fun normalizeGroups(seed: List<LiveGroup>, channels: List<LiveChannel>): List<LiveGroup> {
        val byId = LinkedHashMap<String, LiveGroup>()
        seed.sortedBy { it.order }.forEach { byId[it.id] = it }
        var nextOrder = (seed.maxOfOrNull { it.order } ?: 0) + 1
        for (channel in channels) {
            if (byId.containsKey(channel.groupId)) continue
            byId[channel.groupId] = LiveGroup(channel.groupId, channel.groupName, nextOrder++)
        }
        return byId.values.toList()
    }

    /** 分组顺序 + 组内 order 排序，然后按展示顺序发台号（1 起）。 */
    private fun orderChannels(groups: List<LiveGroup>, channels: List<LiveChannel>): List<LiveChannel> {
        val groupOrder = groups.withIndex().associate { it.value.id to it.index }
        return channels
            .sortedWith(
                compareBy<LiveChannel>(
                    { groupOrder[it.groupId] ?: Int.MAX_VALUE },
                    { it.order },
                    { it.name },
                ),
            )
            .mapIndexed { index, channel -> channel.copy(number = index + 1) }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun pick(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.optString(key, "").trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return null
    }

    private fun slug(name: String, index: Int): String {
        val base = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return base.ifBlank { "ch${index + 1}" }
    }

    private companion object {
        const val TAG = "LiveSeed"
    }
}
