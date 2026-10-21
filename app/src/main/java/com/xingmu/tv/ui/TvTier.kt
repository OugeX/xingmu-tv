package com.xingmu.tv.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 大字号模式（docs/UI_SPEC.md §0.6）：独立于 A/C 显示模式，可叠加。
 *  - 标准 1.0×：6 列（按屏宽 4/5/6）
 *  - 大字 1.3×：4 列
 *  - 超大 1.6×：3 列
 *
 * 所有字号乘 [scale]，列数由 [columns] 决定；不使用系统 fontScale 硬顶（会挤爆布局）。
 * 持久化在 SharedPreferences，冷启动首帧即按用户档位渲染，避免先小后大闪烁。
 */
enum class FontTier(val label: String, val scale: Float, val columns: Int) {
    STANDARD("标准", 1.0f, 0),
    LARGE("大字", 1.3f, 4),
    XLARGE("超大", 1.6f, 3);

    val isLarge: Boolean get() = this != STANDARD
}

val LocalTvTier = staticCompositionLocalOf { FontTier.STANDARD }

class DisplayPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("tvbox_display", Context.MODE_PRIVATE)

    fun readTier(): FontTier = when (prefs.getString(KEY_TIER, null)) {
        FontTier.LARGE.name -> FontTier.LARGE
        FontTier.XLARGE.name -> FontTier.XLARGE
        else -> FontTier.STANDARD
    }

    fun writeTier(tier: FontTier) {
        prefs.edit().putString(KEY_TIER, tier.name).apply()
    }

    private companion object {
        const val KEY_TIER = "font_tier"
    }
}
