package com.xingmu.tv.data

/**
 * MacCMS 列表查询串拼装（t31）。
 *
 * bfzy1 实测（docs 与 t31 任务单）：
 *  - `t=<typeId>` 有效：一级分类 20/30/39/45/58，子分类如 喜剧片=22（total 7442 / 373 页）；
 *  - `year=YYYY` 有效：`t=22&year=2026` → total 165；
 *  - `area=` / `by=` 被服务端忽略 → 不再拼 `area`，`by` 仅作为兼容参数保留（对端忽略）。
 *
 * 纯函数：单测直接断言拼装结果，不依赖 Android/网络。
 */
object MacCmsQuery {

    /**
     * @param typeId 服务端真实分类 id（一级或子分类）；null 表示不限制分类
     * @param page 1-based
     * @param order 排序键（time/hits）；服务端目前忽略，保留兼容
     * @param year 年份筛选，空串/null 表示不限
     */
    fun list(typeId: Int?, page: Int, order: String, year: String?): String {
        val params = ArrayList<Pair<String, String>>(5)
        params.add("ac" to "detail")
        if (typeId != null && typeId > 0) params.add("t" to typeId.toString())
        params.add("pg" to page.coerceAtLeast(1).toString())
        params.add("by" to order)
        val normalizedYear = year?.trim().orEmpty()
        if (normalizedYear.isNotEmpty()) params.add("year" to normalizedYear)
        return "?" + params.joinToString("&") { "${it.first}=${it.second}" }
    }

    /** 本类搜索（t34 §7）：服务端  +  组合，只在当前分类内搜。 */
    fun searchInCategory(typeId: Int?, keyword: String, page: Int): String {
        val params = ArrayList<Pair<String, String>>(5)
        params.add("ac" to "detail")
        params.add("wd" to java.net.URLEncoder.encode(keyword, "UTF-8"))
        if (typeId != null && typeId > 0) params.add("t" to typeId.toString())
        params.add("pg" to page.coerceAtLeast(1).toString())
        return "?" + params.joinToString("&") { "${it.first}=${it.second}" }
    }

    /** 能力探测用：临时带上 area（正常路径不传；bfzy1 会忽略）。 */
    fun listWithArea(typeId: Int?, page: Int, order: String, area: String): String =
        list(typeId, page, order, null) + "&area=" + area

    /** 缓存键必须包含 year，否则切年份会命中上一年份的文件缓存。 */
    fun listCacheKey(sourceId: String, typeId: Int?, page: Int, order: String, year: String?): String =
        "list|$sourceId|${typeId ?: 0}|$page|$order|${year?.trim().orEmpty().ifEmpty { "-" }}"
}
