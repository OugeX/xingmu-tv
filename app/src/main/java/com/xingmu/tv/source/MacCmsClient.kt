package com.xingmu.tv.source

import com.xingmu.tv.core.ErrorCodes
import com.xingmu.tv.core.Result
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.data.Episode
import com.xingmu.tv.data.VodDetail
import com.xingmu.tv.data.VodSummary
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * MacCMS / macjson 适配器（bfzy1.tv 的 macapi1 网关 + 标准 MacCMS 站）。
 *
 * 关键约束（docs/PLAN_SOURCES.md §8）：
 * - 列表页只用 `ac=list`，详情/搜索才 `ac=detail`（45KB vs 164KB）；
 * - 响应宽松解析：只取需要的 ~30 个字段，未知字段忽略，不反射；
 * - header 用源配置（bfzy1 必须带 Referer）。
 */
object MacCmsClient {

    private const val TAG = "MacCms"
    const val DEFAULT_UA = "okhttp/4.12.0"
    private const val MAX_BODY = 2 * 1024 * 1024

    data class CategoryItem(val id: Int, val parentId: Int, val name: String)

    data class ClassPage(
        val categories: List<CategoryItem>,
        val httpCode: Int,
        val code: Int,
        val latencyMs: Long,
    )

    data class Page(
        val items: List<VodSummary>,
        val total: Int,
        val pageCount: Int,
        val latencyMs: Long,
    )

    fun apiUrl(src: ParsedSource, params: List<Pair<String, String>>): String {
        val q = params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val base = if (src.baseUrl.isEmpty()) src.endpoint else src.baseUrl
        return if (base.contains("?")) "$base&$q" else "$base?$q"
    }

    private fun headersOf(src: ParsedSource): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        if (src.headers["User-Agent"] == null) h["User-Agent"] = DEFAULT_UA
        h.putAll(src.headers)
        return h
    }

    private suspend fun get(src: ParsedSource, url: String): Result<HttpLayer.TextResponse> =
        HttpLayer.getText(
            url = url,
            headers = headersOf(src),
            timeoutMs = src.timeoutMs.toLong(),
            maxBytes = MAX_BODY,
            userAgent = src.headers["User-Agent"] ?: DEFAULT_UA,
        )

    /** L3 分类树探测：`ac=list&t=0`。 */
    suspend fun fetchClasses(src: ParsedSource): Result<ClassPage> {
        val url = apiUrl(src, listOf("ac" to "list", "t" to "0"))
        return when (val r = get(src, url)) {
            is Result.Err -> r
            is Result.Ok -> {
                val body = r.value
                val obj = parseEnvelope(body.body)
                    ?: return Result.failure(ErrorCodes.PARSE, "分类树不是合法 JSON（HTTP ${body.httpCode}）")
                val items = ArrayList<CategoryItem>()
                val arr = obj.optJSONArray("class")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = intValue(o, "type_id") ?: continue
                        items.add(
                            CategoryItem(
                                id = id,
                                parentId = intValue(o, "type_pid") ?: 0,
                                name = stringValue(o, "type_name") ?: "",
                            ),
                        )
                    }
                }
                Result.Ok(ClassPage(items, body.httpCode, intValue(obj, "code") ?: 0, body.latencyMs))
            }
        }
    }

    /** 列表页：`ac=list&t=&pg=`（不拉 detail）。 */
    suspend fun fetchList(
        src: ParsedSource,
        typeId: Int?,
        page: Int,
        sort: SourceRepository.Sort,
    ): Result<Page> {
        val params = ArrayList<Pair<String, String>>()
        params.add("ac" to "list")
        if (typeId != null) params.add("t" to typeId.toString())
        params.add("pg" to page.coerceAtLeast(1).toString())
        if (sort == SourceRepository.Sort.HOT) params.add("by" to "hits")
        params.add("limit" to src.pageSize.toString())
        return fetchPage(src, apiUrl(src, params))
    }

    /** 搜索：`ac=detail&wd=`（保证字段完整，plan §8.1）。 */
    suspend fun search(src: ParsedSource, keyword: String, page: Int): Result<Page> {
        val params = listOf(
            "ac" to "detail",
            "wd" to keyword,
            "pg" to page.coerceAtLeast(1).toString(),
            "limit" to src.pageSize.toString(),
        )
        return fetchPage(src, apiUrl(src, params))
    }

    suspend fun fetchDetail(src: ParsedSource, vodId: String): Result<VodDetail> {
        val url = apiUrl(src, listOf("ac" to "detail", "ids" to vodId))
        return when (val r = get(src, url)) {
            is Result.Err -> r
            is Result.Ok -> {
                val obj = parseEnvelope(r.value.body)
                    ?: return Result.failure(ErrorCodes.PARSE, "详情不是合法 JSON")
                val arr = obj.optJSONArray("list")
                if (arr == null || arr.length() == 0) {
                    return Result.failure(ErrorCodes.NO_PLAY_URL, "详情为空（vodId=$vodId）")
                }
                val detail = arr.optJSONObject(0)?.let { detailOf(it, src.id) }
                    ?: return Result.failure(ErrorCodes.PARSE, "详情字段缺失")
                if (detail.episodesByFrom.values.all { it.isEmpty() }) {
                    return Result.failure(ErrorCodes.NO_PLAY_URL, "该作品没有可用播放地址")
                }
                Result.Ok(detail)
            }
        }
    }

    /**
     * L4 内容探测：**先 `ac=list` 拿一个 vod_id，再用 `ids=` 取单条 detail** 检查 vod_play_url 非空。
     * 不能直接 `ac=detail&pg=1&limit=1`——MacCMS 会忽略 limit 返回 20 条（164KB+），
     * 小内存盒子/2MB 上限下会截断导致误判。
     */
    suspend fun probeContent(src: ParsedSource): Result<Pair<Int, Long>> {
        var latency = 0L
        var vodId: String? = null
        when (val latest = fetchPage(src, apiUrl(src, listOf("ac" to "list", "pg" to "1", "limit" to "1")))) {
            is Result.Err -> return latest
            is Result.Ok -> {
                latency += latest.value.latencyMs
                vodId = latest.value.items.firstOrNull()?.vodId
            }
        }
        if (vodId == null) {
            // 首页为空（冷门源/分类站点）时按分类树第一个分类再取一页
            when (val classes = fetchClasses(src)) {
                is Result.Err -> return classes
                is Result.Ok -> {
                    latency += classes.value.latencyMs
                    val typeId = classes.value.categories.firstOrNull()?.id
                        ?: return Result.failure(ErrorCodes.NO_PLAY_URL, "内容探测：分类树为空")
                    when (val page = fetchList(src, typeId, 1, SourceRepository.Sort.LATEST)) {
                        is Result.Err -> return page
                        is Result.Ok -> {
                            latency += page.value.latencyMs
                            vodId = page.value.items.firstOrNull()?.vodId
                        }
                    }
                }
            }
        }
        val id = vodId ?: return Result.failure(ErrorCodes.NO_PLAY_URL, "内容探测：源里没有可用作品")
        return when (val r = get(src, apiUrl(src, listOf("ac" to "detail", "ids" to id)))) {
            is Result.Err -> r
            is Result.Ok -> {
                val obj = parseEnvelope(r.value.body)
                    ?: return Result.failure(ErrorCodes.PARSE, "内容探测响应不是合法 JSON（HTTP ${r.value.httpCode}）")
                val first = obj.optJSONArray("list")?.optJSONObject(0)
                    ?: return Result.failure(ErrorCodes.NO_PLAY_URL, "内容探测：详情为空（id=$id）")
                val playUrl = stringValue(first, "vod_play_url") ?: ""
                if (playUrl.isBlank()) {
                    return Result.failure(ErrorCodes.NO_PLAY_URL, "内容探测：首条 detail 的 play_url 为空")
                }
                Result.Ok(1 to (latency + r.value.latencyMs))
            }
        }
    }

    private suspend fun fetchPage(src: ParsedSource, url: String): Result<Page> {
        return when (val r = get(src, url)) {
            is Result.Err -> r
            is Result.Ok -> {
                val obj = parseEnvelope(r.value.body)
                    ?: return Result.failure(ErrorCodes.PARSE, "列表不是合法 JSON（HTTP ${r.value.httpCode}）")
                val code = intValue(obj, "code")
                if (code != null && code != 1) {
                    val msg = stringValue(obj, "msg") ?: "code=$code"
                    return Result.failure(ErrorCodes.HTTP, "源返回错误：$msg")
                }
                val arr = obj.optJSONArray("list") ?: JSONArray()
                val items = ArrayList<VodSummary>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    summaryOf(o, src.id)?.let { items.add(it) }
                }
                Result.Ok(
                    Page(
                        items = items,
                        total = intValue(obj, "total") ?: items.size,
                        pageCount = intValue(obj, "pagecount") ?: 0,
                        latencyMs = r.value.latencyMs,
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------------- 解析

    /** 去掉 JSON 前的 BOM/杂字符，解析失败返回 null（宽松）。 */
    fun parseEnvelope(body: String): JSONObject? {
        val trimmed = body.trim()
        val start = trimmed.indexOf('{')
        if (start < 0) return null
        return try {
            JSONObject(if (start == 0) trimmed else trimmed.substring(start))
        } catch (e: Exception) {
            TvLog.w(TAG, "JSON 解析失败：${e.message}")
            null
        }
    }

    fun summaryOf(o: JSONObject, sourceId: String): VodSummary? {
        val id = stringValue(o, "vod_id") ?: return null
        val name = stringValue(o, "vod_name")?.trim()
        if (name.isNullOrEmpty()) return null
        return VodSummary(
            vodId = id,
            sourceId = sourceId,
            name = name,
            pic = stringValue(o, "vod_pic") ?: "",
            remarks = (stringValue(o, "vod_remarks") ?: "").take(20),
            year = stringValue(o, "vod_year") ?: "",
            typeId = stringValue(o, "type_id") ?: "",
            typeName = stringValue(o, "type_name") ?: "",
            score = stringValue(o, "vod_score") ?: "",
            picThumb = stringValue(o, "vod_pic_thumb") ?: "",
        )
    }

    fun detailOf(o: JSONObject, sourceId: String): VodDetail? {
        val id = stringValue(o, "vod_id") ?: return null
        val name = stringValue(o, "vod_name")?.trim() ?: return null
        val froms = (stringValue(o, "vod_play_from") ?: "").split("$$$").filter { it.isNotBlank() }
        val urlGroups = (stringValue(o, "vod_play_url") ?: "").split("$$$")
        val byFrom = LinkedHashMap<String, List<Episode>>()
        for (i in froms.indices) {
            val group = urlGroups.getOrNull(i) ?: continue
            byFrom[froms[i]] = parseEpisodes(group)
        }
        return VodDetail(
            vodId = id,
            sourceId = sourceId,
            name = name,
            pic = stringValue(o, "vod_pic") ?: "",
            slidePic = stringValue(o, "vod_pic_slide") ?: "",
            score = stringValue(o, "vod_score") ?: "",
            year = stringValue(o, "vod_year") ?: "",
            area = stringValue(o, "vod_area") ?: "",
            lang = stringValue(o, "vod_lang") ?: "",
            duration = stringValue(o, "vod_duration") ?: "",
            remarks = stringValue(o, "vod_remarks") ?: "",
            actors = stringValue(o, "vod_actor") ?: "",
            director = stringValue(o, "vod_director") ?: "",
            content = stripHtml(stringValue(o, "vod_content")),
            typeName = stringValue(o, "type_name") ?: "",
            playFroms = froms,
            episodesByFrom = byFrom,
        )
    }

    /** `#` 分隔剧集、`$` 分隔名称与地址；地址不是 http(s) 的条目丢弃。 */
    fun parseEpisodes(group: String): List<Episode> {
        val out = ArrayList<Episode>()
        val parts = group.split("#")
        var index = 1
        for (raw in parts) {
            if (raw.isBlank()) continue
            val pair = raw.split("$", limit = 2)
            val title: String
            val url: String
            if (pair.size == 2) {
                title = pair[0].trim()
                url = pair[1].trim()
            } else {
                title = "第${index}集"
                url = pair[0].trim()
            }
            if (!UrlRules.isHttpUrl(url)) continue
            out.add(Episode(index = index, title = title.ifEmpty { "第${index}集" }, url = url, playFrom = ""))
            index++
        }
        return out
    }

    fun stripHtml(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        var t = s
            .replace(Regex("<[^>]{0,2000}>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        t = t.replace(Regex("\\s+"), " ").trim()
        return if (t.length > 2000) t.take(2000) else t
    }

    private fun stringValue(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.get(key)) {
            is String -> v
            is Number -> if (v.toDouble() == Math.floor(v.toDouble())) v.toLong().toString() else v.toString()
            is Boolean -> v.toString()
            else -> null
        }
    }

    private fun intValue(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.get(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }
}
