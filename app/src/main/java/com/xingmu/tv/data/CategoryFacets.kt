package com.xingmu.tv.data

import org.json.JSONObject

/**
 * 分类页体验增强的纯逻辑（t34），全部可在 JVM 单测里覆盖：
 *  - 子分类数量角标：从列表响应解析 `total`；
 *  - 筛选能力探测：比较带 `area`/`by` 前后的 `total` 是否变化（bfzy1 不支持两者）；
 *  - 空结果放宽：`total=0` 时先去掉年份重试，再给「查看全部」；
 *  - 三条榜单：本周更新 / 评分最高（过滤 <=1.0 假分）/ 最近完结。
 */
object CategoryFacets {

    /** 子分类角标：解析 `total`；解析失败返回 null（UI 不显示角标而不是显示 0）。 */
    fun parseTotal(json: String): Int? = runCatching {
        val root = JSONObject(json)
        if (root.optInt("code", 1) != 1) return@runCatching null
        if (!root.has("total")) return@runCatching null
        root.optInt("total", 0).takeIf { it >= 0 }
    }.getOrNull()

    /** 角标缓存是否新鲜（默认 24h）。 */
    fun isFresh(savedAtMs: Long, nowMs: Long, ttlMs: Long = COUNT_TTL_MS): Boolean =
        savedAtMs > 0 && nowMs - savedAtMs in 0 until ttlMs

    const val COUNT_TTL_MS = 24L * 60 * 60 * 1000
    const val PROBE_TTL_MS = 7L * 24 * 60 * 60 * 1000
}

/** 单个筛选维度在某源上的支持度。 */
enum class FilterSupport { SUPPORTED, UNSUPPORTED, PARTIAL, UNKNOWN }

/** 探测结论（UI 据此决定是否显示「地区」「排序·本页」）。 */
data class FilterCapability(
    val area: FilterSupport = FilterSupport.UNKNOWN,
    val sort: FilterSupport = FilterSupport.UNKNOWN,
) {
    val showArea: Boolean get() = area == FilterSupport.SUPPORTED || area == FilterSupport.PARTIAL
    val showSort: Boolean get() = sort == FilterSupport.SUPPORTED || sort == FilterSupport.PARTIAL
    val partialNote: Boolean get() = area == FilterSupport.PARTIAL || sort == FilterSupport.PARTIAL
}

/**
 * 能力探测判定（纯函数）：
 *  - baseline<=0 无法判定 → UNKNOWN；
 *  - 带参请求 total 变化 → 该参数被服务端采纳 → SUPPORTED；
 *  - 带参请求 total 不变 → 被忽略 → UNSUPPORTED；
 *  - 多源：支持数 > 反对数 → SUPPORTED/PARTIAL（部分源不支持），反之 UNSUPPORTED。
 */
object CapabilityDecider {

    fun decide(baseline: Int, withArea: Int?, withSort: Int?): Pair<FilterSupport, FilterSupport> {
        val area = decideOne(baseline, withArea)
        val sort = decideOne(baseline, withSort)
        return area to sort
    }

    fun decideOne(baseline: Int, probed: Int?): FilterSupport = when {
        baseline <= 0 || probed == null || probed < 0 -> FilterSupport.UNKNOWN
        probed != baseline -> FilterSupport.SUPPORTED
        else -> FilterSupport.UNSUPPORTED
    }

    /** 多源多数决：supported 源数 / 反对源数。部分源不支持时标 PARTIAL。 */
    fun majority(supportedCount: Int, unsupportedCount: Int): FilterSupport = when {
        supportedCount == 0 && unsupportedCount == 0 -> FilterSupport.UNKNOWN
        supportedCount >= unsupportedCount && supportedCount > 0 ->
            if (unsupportedCount > 0) FilterSupport.PARTIAL else FilterSupport.SUPPORTED
        else -> FilterSupport.UNSUPPORTED
    }
}

/**
 * 空结果放宽顺序（纯函数）：
 *  1. total>0 → 直接用当前条件；
 *  2. total==0 且带年份 → 去掉年份重试一次；
 *  3. 仍然 0 → 提示「该组合暂无内容」+「查看全部」。
 */
object CategoryFallback {

    enum class Step { RELAX_YEAR, SHOW_EMPTY }

    /** 返回放宽计划：空表示不需要放宽。 */
    fun plan(total: Int, hasYearFilter: Boolean): List<Step> = when {
        total > 0 -> emptyList()
        hasYearFilter -> listOf(Step.RELAX_YEAR, Step.SHOW_EMPTY)
        else -> listOf(Step.SHOW_EMPTY)
    }

    fun needsRelaxYear(total: Int, hasYearFilter: Boolean): Boolean =
        total == 0 && hasYearFilter
}

/** 三条榜单（t34 §4）。 */
object CategoryBoards {

    /** 本周更新最多：按更新时间倒序（VIP 时间字段为 vod_time_add）。 */
    fun mostUpdated(items: List<VodSummary>, limit: Int = 8): List<VodSummary> =
        items.filter { it.updatedAtMs > 0 }.sortedByDescending { it.updatedAtMs }.take(limit)

    /**
     * 评分最高：过滤镜像站假分。
     *  - <=1.0（默认值/占位）直接剔除；
     *  - 恰好 10.0 也是 bfzy1 的整齐假分（captain 实测 10.0/6.0/4.0/1.0），排行榜不采用。
     */
    fun isTrustedScore(score: String): Boolean {
        val value = VodRanking.effectiveScore(score)
        return value > 1.0 && value < 10.0
    }

    fun topRated(items: List<VodSummary>, limit: Int = 8): List<VodSummary> =
        items.filter { isTrustedScore(it.score) }
            .sortedWith(
                compareByDescending<VodSummary> { VodRanking.effectiveScore(it.score) }
                    .thenByDescending { it.updatedAtMs },
            )
            .take(limit)

    /** 最近完结：remarks 含「全/完结/已完结」的，按更新时间倒序。 */
    fun recentlyCompleted(items: List<VodSummary>, limit: Int = 8): List<VodSummary> =
        items.filter { isCompleted(it.remarks) }.sortedByDescending { it.updatedAtMs }.take(limit)

    fun isCompleted(remarks: String): Boolean {
        val text = remarks.trim()
        if (text.isEmpty()) return false
        return text.contains("完结") || text.contains("全")
    }
}
