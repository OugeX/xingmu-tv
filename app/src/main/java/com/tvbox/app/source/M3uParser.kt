package com.tvbox.app.source

import com.tvbox.app.data.Channel
import com.tvbox.app.data.ChannelUrl

/**
 * m3u / m3u8 播放列表解析（直播源导入）。
 *
 * 支持的语法子集（PLAN_SOURCES §9.1）：
 * - `#EXTM3U` 头
 * - `#EXTINF:-1 tvg-id="cctv1" tvg-name="..." tvg-logo="..." group-title="央视",频道名`
 * - `#EXTGRP:分组`、`#EXTVLCOPT:http-user-agent=...`（只取 UA/Referer）
 * - 裸 URL 行（无 EXTINF 时按文件名生成频道名）
 *
 * 解析失败（无 #EXTM3U 或无任何 URL）返回空列表，由调用方降级。
 */
object M3uParser {

    private val ATTR_RE = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    data class ParseResult(
        val channels: List<Channel>,
        val totalLines: Int,
        val skipped: Int,
    )

    fun parse(text: String, sourceId: String, fallbackName: String = "直播源", maxLines: Int = 20000): ParseResult {
        val lines = text.lineSequence().take(maxLines).toList()
        val channels = ArrayList<Channel>()
        var currentName: String? = null
        var currentGroup: String? = null
        var currentLogo: String? = null
        var currentTvg: String? = null
        var currentDesc: String? = null
        var currentHeaders = emptyMap<String, String>()
        var skipped = 0
        var order = 0
        val seenKeys = HashSet<String>()

        fun flush(url: String) {
            if (url.isBlank() || !UrlRules.isHttpUrl(url)) {
                skipped++
                return
            }
            order++
            val name = currentName?.trim()?.ifEmpty { null } ?: "$fallbackName ${order}"
            val group = currentGroup?.trim()?.ifEmpty { null } ?: "其他"
            val key = (currentTvg?.trim()?.lowercase()?.ifEmpty { null }) ?: (group + "|" + name.replace(Regex("\\s+"), "").lowercase())
            if (!seenKeys.add(key)) {
                skipped++
                return
            }
            channels.add(
                Channel(
                    id = UrlRules.sanitizeId("$sourceId-$order"),
                    name = name.take(60),
                    group = group.take(20),
                    logo = currentLogo,
                    tvgId = currentTvg,
                    desc = currentDesc,
                    urls = listOf(ChannelUrl(url = url, quality = null, resolution = null, latencyMs = null)),
                ),
            )
            currentName = null
            currentGroup = null
            currentLogo = null
            currentTvg = null
            currentDesc = null
            currentHeaders = emptyMap()
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val comma = line.lastIndexOf(',')
                    currentName = if (comma >= 0) line.substring(comma + 1).trim() else null
                    val attrs = HashMap<String, String>()
                    ATTR_RE.findAll(line.substring(0, if (comma > 0) comma else line.length))
                        .forEach { m -> attrs[m.groupValues[1].lowercase()] = m.groupValues[2] }
                    currentTvg = attrs["tvg-id"]?.ifEmpty { null }
                    currentLogo = attrs["tvg-logo"]?.ifEmpty { null }
                    currentGroup = attrs["group-title"]?.ifEmpty { null }
                    currentName = currentName?.ifEmpty { null } ?: attrs["tvg-name"]?.ifEmpty { null }
                    currentDesc = attrs["tvg-desc"]?.ifEmpty { null }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> currentGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':').trim()
                    when {
                        opt.startsWith("http-user-agent=", true) ->
                            currentHeaders = currentHeaders + ("User-Agent" to opt.substringAfter('=').trim())
                        opt.startsWith("http-referrer=", true) || opt.startsWith("http-referer=", true) ->
                            currentHeaders = currentHeaders + ("Referer" to opt.substringAfter('=').trim())
                    }
                }
                line.startsWith("#") -> Unit
                else -> flush(line)
            }
        }
        return ParseResult(channels, lines.size, skipped)
    }

    /** 按 filters 过滤（includeGroups/excludeGroups/excludeNameRegex）。 */
    fun applyFilters(channels: List<Channel>, filters: M3uFilters): List<Channel> {
        var out = channels
        if (filters.includeGroups.isNotEmpty()) {
            out = out.filter { ch -> filters.includeGroups.any { it.equals(ch.group, ignoreCase = true) } }
        }
        if (filters.excludeGroups.isNotEmpty()) {
            out = out.filter { ch -> filters.excludeGroups.none { it.equals(ch.group, ignoreCase = true) } }
        }
        val re = filters.excludeNameRegex?.let { runCatching { Regex(it) }.getOrNull() }
        if (re != null) out = out.filter { ch -> !re.containsMatchIn(ch.name) }
        return out
    }

    /** 手工 channels[] 定义并入解析结果（同键覆盖/合并 URL）。 */
    fun mergeChannelDefs(channels: List<Channel>, defs: List<ChannelDef>, sourceId: String): List<Channel> {
        if (defs.isEmpty()) return channels
        val byKey = LinkedHashMap<String, Channel>()
        for (ch in channels) {
            byKey[ch.tvgId?.lowercase() ?: (ch.group + "|" + ch.name.replace(Regex("\\s+"), "").lowercase())] = ch
        }
        var order = channels.size
        for (def in defs) {
            order++
            val key = def.tvgId?.lowercase() ?: ((def.group ?: "其他") + "|" + def.name.replace(Regex("\\s+"), "").lowercase())
            val existing = byKey[key]
            val urls = (existing?.urls?.map { it.url } ?: emptyList()).toMutableList()
            for (u in def.urls) if (!urls.contains(u)) urls.add(u)
            byKey[key] = Channel(
                id = existing?.id ?: UrlRules.sanitizeId("$sourceId-$order"),
                name = def.name.take(60),
                group = (def.group ?: existing?.group ?: "其他").take(20),
                logo = def.logo ?: existing?.logo,
                tvgId = def.tvgId ?: existing?.tvgId,
                desc = def.desc ?: existing?.desc,
                urls = urls.map { ChannelUrl(url = it, quality = null, resolution = null, latencyMs = null) },
            )
        }
        return byKey.values.toList()
    }
}
