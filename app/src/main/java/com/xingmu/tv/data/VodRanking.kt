package com.xingmu.tv.data

/**
 * 热度排序（t31，captain 实测校正）。
 *
 * bfzy1 的 `vod_hits` 恒为 0、`vod_score` 是镜像站的假分（10.0/6.0/4.0/1.0 这类整齐值），
 * 所以热度不能用 hits/score 直接排。真实信号是「同一作品在几个源出现」：
 *
 *   1. sourceCount desc      —— 跨源重合度，最真实
 *   2. vod_time_add desc     —— 同源数时用新旧兜底，避免首页全是老片
 *   3. vod_score desc        —— 仅 tie-break，且 <=1.0 的假分（1.0 是默认值）按 0 处理
 *   4. vod_hits              —— 全 0，不参与排序
 *
 * 纯函数：单测覆盖「hits 全 0 / score 全 1.0 / 只有单源作品」三种边界。
 */
object VodRanking {

    /**
     * **<= 1.0** 视为镜像站默认假分（bfzy1 实测：10.0/6.0/4.0/1.0 这类整齐值，1.0 是默认值），返回 0；
     * 只有 >1.0 才当作真实评分。
     */
    fun effectiveScore(score: String): Double {
        val value = score.trim().toDoubleOrNull() ?: return 0.0
        return if (value <= 1.0) 0.0 else value
    }

    /** 热度排序：sourceCount → 更新时间 → 评分（无效分归零）；完全相同时保持输入顺序。 */
    fun rankHot(items: List<VodSummary>): List<VodSummary> = items.sortedWith(
        compareByDescending<VodSummary> { it.sourceCount }
            .thenByDescending { it.updatedAtMs }
            .thenByDescending { effectiveScore(it.score) },
    )

    /** 评分排序（「排序·本页」里选评分时用），假分同样按 0。 */
    fun rankByScore(items: List<VodSummary>): List<VodSummary> = items.sortedWith(
        compareByDescending<VodSummary> { effectiveScore(it.score) }
            .thenByDescending { it.sourceCount },
    )
}
