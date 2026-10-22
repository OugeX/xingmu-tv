package com.xingmu.tv.data

import com.xingmu.tv.core.AppError
import com.xingmu.tv.core.ErrorCodes
import com.xingmu.tv.core.Result
import org.json.JSONArray
import org.json.JSONObject

/**
 * MacCMS JSON 解析（ac=list / ac=detail 同构：都返回 `{code,msg,list:[...]}`）。
 *
 * 兼容性要求（docs/PLAN_ARCHITECTURE.md §3.4）：
 * - `type_id` 可能是数字或字符串，一律按字符串读；
 * - 未知字段忽略；
 * - `vod_play_from` / `vod_play_url` 都是 `$$$` 分组、组内 `#` 分集、`$` 分名称与地址；
 * - 只接受 http/https 播放地址。
 */
object MacCmsParser {

    /** t31：列表 + 服务端 total/pagecount（分页 UI 用「共 N 部」而不是只数已加载）。 */
    fun parseListPage(json: String, sourceId: String): Result<VodPage> = try {
        val root = JSONObject(json)
        val code = root.optInt("code", 1)
        if (code != 1) {
            Result.Err(AppError(ErrorCodes.SOURCE_OFFLINE, "源返回错误：" + root.optString("msg", "code=$code")))
        } else {
            val array = root.optJSONArray("list") ?: JSONArray()
            val items = ArrayList<VodSummary>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val summary = toSummary(obj, sourceId)
                if (summary.vodId.isNotBlank() && summary.name.isNotBlank()) items.add(summary)
            }
            Result.Ok(
                VodPage(
                    items = items,
                    total = root.optInt("total", items.size),
                    pageCount = root.optInt("pagecount", 0),
                ),
            )
        }
    } catch (t: Throwable) {
        Result.Err(AppError(ErrorCodes.PARSE, "列表解析失败：" + (t.message ?: ""), t))
    }

    fun parseList(json: String, sourceId: String): Result<List<VodSummary>> = try {
        val root = JSONObject(json)
        val code = root.optInt("code", 1)
        if (code != 1) {
            Result.Err(AppError(ErrorCodes.SOURCE_OFFLINE, "源返回错误：${root.optString("msg", "code=$code")}"))
        } else {
            val array = root.optJSONArray("list") ?: JSONArray()
            val items = ArrayList<VodSummary>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val summary = toSummary(obj, sourceId)
                if (summary.vodId.isNotBlank() && summary.name.isNotBlank()) items.add(summary)
            }
            Result.Ok(items)
        }
    } catch (t: Throwable) {
        Result.Err(AppError(ErrorCodes.PARSE, "列表解析失败：${t.message}", t))
    }

    fun parseDetail(json: String, sourceId: String, expectedVodId: String): Result<VodDetail> = try {
        val root = JSONObject(json)
        val code = root.optInt("code", 1)
        val array = root.optJSONArray("list")
        if (code != 1 || array == null || array.length() == 0) {
            Result.Err(
                AppError(
                    ErrorCodes.NO_PLAY_URL,
                    "详情为空：${root.optString("msg", "code=$code")}",
                ),
            )
        } else {
            var picked: JSONObject? = null
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (expectedVodId.isBlank() || obj.optString("vod_id") == expectedVodId) {
                    picked = obj
                    break
                }
                if (picked == null) picked = obj
            }
            if (picked == null) {
                Result.Err(AppError(ErrorCodes.PARSE, "详情缺少作品对象"))
            } else {
                Result.Ok(toDetail(picked, sourceId))
            }
        }
    } catch (t: Throwable) {
        Result.Err(AppError(ErrorCodes.PARSE, "详情解析失败：${t.message}", t))
    }

    private fun toSummary(o: JSONObject, sourceId: String): VodSummary {
        val typeName = o.optString("vod_class").ifBlank { o.optString("type_name") }
        return VodSummary(
            vodId = o.optString("vod_id").trim(),
            sourceId = sourceId,
            name = o.optString("vod_name").trim(),
            pic = o.optString("vod_pic").trim(),
            remarks = o.optString("vod_remarks").trim(),
            year = o.optString("vod_year").trim(),
            typeId = o.optString("type_id").trim(),
            typeName = typeName.trim(),
            score = o.optString("vod_score").trim(),
            picThumb = o.optString("vod_pic_thumb").trim(),
            area = o.optString("vod_area").trim(),
            updatedAtMs = o.optLong("vod_time_add", 0L) * 1000L,
            hits = o.optLong("vod_hits", 0L),
        )
    }

    private fun toDetail(o: JSONObject, sourceId: String): VodDetail {
        val froms = splitGroups(o.optString("vod_play_from"))
        val urls = splitGroups(o.optString("vod_play_url"))
        val episodesByFrom = LinkedHashMap<String, List<Episode>>()
        froms.forEachIndexed { groupIndex, from ->
            val rawGroup = urls.getOrNull(groupIndex).orEmpty()
            val episodes = parseEpisodes(rawGroup, from)
            if (episodes.isNotEmpty() || !episodesByFrom.containsKey(from)) {
                episodesByFrom[from] = episodes
            }
        }
        val playFroms = episodesByFrom.keys.toList()
        return VodDetail(
            vodId = o.optString("vod_id").trim(),
            sourceId = sourceId,
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
            playFroms = playFroms,
            episodesByFrom = episodesByFrom,
        )
    }

    private fun parseEpisodes(group: String, from: String): List<Episode> {
        if (group.isBlank()) return emptyList()
        val result = ArrayList<Episode>()
        val segments = group.split('#')
        for ((i, segment) in segments.withIndex()) {
            val text = segment.trim()
            if (text.isEmpty()) continue
            val splitAt = text.indexOf('$')
            if (splitAt <= 0) continue
            val title = text.substring(0, splitAt).trim()
            val url = text.substring(splitAt + 1).trim()
            if (!isHttpUrl(url)) continue
            result.add(
                Episode(
                    index = i + 1,
                    title = title.ifBlank { "第${i + 1}集" },
                    url = url,
                    playFrom = from,
                ),
            )
        }
        return result
    }

    private fun splitGroups(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value.split("$$$").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

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
