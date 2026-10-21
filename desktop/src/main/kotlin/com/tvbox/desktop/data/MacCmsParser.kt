package com.tvbox.desktop.data

import org.json.JSONArray
import org.json.JSONObject

class MacCmsException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * MacCMS JSON 解析（ac=list / ac=detail 同构：`{code,msg,list:[...]}`）。
 * 字段映射与 Android 端 MacCmsParser.kt 保持一致。
 */
object MacCmsParser {

    fun parseList(json: String, source: SourceConfig): List<VodSummary> {
        val root = JSONObject(json)
        val code = root.optInt("code", 1)
        if (code != 1) throw MacCmsException("SOURCE_OFFLINE", "源返回错误：${root.optString("msg", "code=$code")}")
        val array = root.optJSONArray("list") ?: JSONArray()
        val result = ArrayList<VodSummary>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val summary = toSummary(obj, source)
            if (summary.vodId.isNotBlank() && summary.name.isNotBlank()) result += summary
        }
        return result
    }

    fun parseDetail(json: String, source: SourceConfig, expectedVodId: String): VodDetail {
        val root = JSONObject(json)
        val code = root.optInt("code", 1)
        val array = root.optJSONArray("list")
        if (code != 1 || array == null || array.length() == 0) {
            throw MacCmsException("NO_PLAY_URL", "详情为空：${root.optString("msg", "code=$code")}")
        }
        var picked: JSONObject? = null
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (expectedVodId.isBlank() || obj.optString("vod_id") == expectedVodId) {
                picked = obj
                break
            }
            if (picked == null) picked = obj
        }
        return toDetail(picked ?: throw MacCmsException("PARSE", "详情缺少作品对象"), source)
    }

    private fun toSummary(o: JSONObject, source: SourceConfig): VodSummary {
        val typeName = o.optString("vod_class").ifBlank { o.optString("type_name") }.trim()
        val vodId = o.optString("vod_id").trim()
        val name = o.optString("vod_name").trim()
        return VodSummary(
            vodId = vodId,
            sourceId = source.id,
            sourceName = source.name,
            name = name,
            pic = o.optString("vod_pic").trim(),
            remarks = o.optString("vod_remarks").trim(),
            year = o.optString("vod_year").trim(),
            typeId = o.optString("type_id").trim(),
            typeName = typeName,
            score = o.optString("vod_score").trim(),
            refs = listOf(VodRef(source.id, source.name, vodId)),
        )
    }

    private fun toDetail(o: JSONObject, source: SourceConfig): VodDetail {
        val froms = splitGroups(o.optString("vod_play_from"))
        val urls = splitGroups(o.optString("vod_play_url"))
        val episodesByFrom = LinkedHashMap<String, List<Episode>>()
        froms.forEachIndexed { index, from ->
            val episodes = parseEpisodes(urls.getOrNull(index).orEmpty(), from)
            if (episodes.isNotEmpty() || !episodesByFrom.containsKey(from)) episodesByFrom[from] = episodes
        }
        val vodId = o.optString("vod_id").trim()
        return VodDetail(
            vodId = vodId,
            sourceId = source.id,
            sourceName = source.name,
            name = o.optString("vod_name").trim(),
            pic = o.optString("vod_pic").trim(),
            slidePic = o.optString("vod_pic_slide").trim().ifBlank { o.optString("vod_pic").trim() },
            score = o.optString("vod_score").trim(),
            year = o.optString("vod_year").trim(),
            area = o.optString("vod_area").trim(),
            lang = o.optString("vod_lang").trim(),
            duration = o.optString("vod_duration").trim(),
            remarks = o.optString("vod_remarks").trim(),
            actors = o.optString("vod_actor").trim(),
            director = o.optString("vod_director").trim(),
            content = stripHtml(o.optString("vod_content")),
            typeName = o.optString("vod_class").ifBlank { o.optString("type_name") }.trim(),
            playFroms = episodesByFrom.keys.toList(),
            episodesByFrom = episodesByFrom,
        )
    }

    private fun parseEpisodes(group: String, from: String): List<Episode> {
        if (group.isBlank()) return emptyList()
        val result = ArrayList<Episode>()
        group.split('#').forEachIndexed { i, segment ->
            val text = segment.trim()
            if (text.isEmpty()) return@forEachIndexed
            val splitAt = text.indexOf('$')
            if (splitAt <= 0) return@forEachIndexed
            val title = text.substring(0, splitAt).trim()
            val url = text.substring(splitAt + 1).trim()
            if (!isHttpUrl(url)) return@forEachIndexed
            result += Episode(
                index = i + 1,
                title = title.ifBlank { "第${i + 1}集" },
                url = url,
                playFrom = from,
            )
        }
        return result
    }

    private fun splitGroups(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value.split("$$$").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", true) || url.startsWith("https://", true)

    private fun stripHtml(html: String): String {
        if (html.isBlank()) return ""
        return html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }
}
