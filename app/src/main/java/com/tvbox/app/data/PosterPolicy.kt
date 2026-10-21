package com.tvbox.app.data

/**
 * 封面渐进加载策略（t28）——纯函数，便于单测覆盖「thumb 优先 / 回退」决策。
 *
 * 规则（docs/PERF_ROADMAP.md P1-5）：
 * - MacCMS 的 `vod_pic_thumb` 有值 → 首帧用它（服务端已生成缩略图，通常 1/3 体积）；
 * - 没有 → 用 `vod_pic`，但按半尺寸请求（列表卡片本来就只有 150–200dp，原图 300–600px 足够）；
 * - 两者都空 → 不请求，UI 直接显示占位。
 *
 * 低清首帧 + 停滚动 250ms 后升级原尺寸的两段式由 `PosterImage` 执行，
 * 这里的 [PosterPlan] 只负责「用哪个 URL / 什么尺寸」。
 */
data class PosterPlan(
    /** 首帧（低清）地址；空表示没有可用图 */
    val initialUrl: String,
    /** 升级后（高清）地址；与 initialUrl 相同表示没有更高质量可换 */
    val fullUrl: String,
    val initialWidth: Int,
    val initialHeight: Int,
    val fullWidth: Int,
    val fullHeight: Int,
) {
    val hasImage: Boolean get() = initialUrl.isNotBlank()

    /** 升级是否有意义：URL 不同，或同 URL 但请求尺寸变大。 */
    val canUpgrade: Boolean
        get() = hasImage && (fullUrl != initialUrl || fullWidth > initialWidth || fullHeight > initialHeight)
}

object PosterPolicy {

    /**
     * @param pic 原图（vod_pic）
     * @param picThumb 缩略图（vod_pic_thumb，可能为空）
     * @param width 卡片请求宽（px）
     * @param height 卡片请求高（px）
     */
    fun plan(pic: String, picThumb: String, width: Int, height: Int): PosterPlan {
        val fullUrl = pic.trim().ifBlank { picThumb.trim() }
        val thumbUrl = picThumb.trim().ifBlank { fullUrl }
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        // 有独立缩略图 → 缩略图按卡片尺寸；否则原图先按半尺寸出图（低端盒子少解一半像素）
        val useDedicatedThumb = thumbUrl.isNotBlank() && thumbUrl != fullUrl
        val initialWidth = if (useDedicatedThumb) safeWidth else halfSize(safeWidth)
        val initialHeight = if (useDedicatedThumb) safeHeight else halfSize(safeHeight)
        return PosterPlan(
            initialUrl = if (thumbUrl.isNotBlank()) thumbUrl else fullUrl,
            fullUrl = fullUrl.ifBlank { thumbUrl },
            initialWidth = initialWidth,
            initialHeight = initialHeight,
            fullWidth = safeWidth,
            fullHeight = safeHeight,
        )
    }

    /** 半尺寸下限 1，避免 1dp 卡片算出 0 导致 Coil 异常。 */
    fun halfSize(size: Int): Int = (size / 2).coerceAtLeast(1)

    /** 列表滚动判定 → Coil Precision：滚动中用 INEXACT（允许低清缩放），停稳后用 EXACT。 */
    fun precisionFor(scrolling: Boolean): Boolean = !scrolling
}
