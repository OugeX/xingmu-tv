package com.xingmu.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 双模式主题（docs/UI_SPEC.md §1）：
 *  - A 影院模式：深黑底 + 金色主色，富视觉；
 *  - C 保命模式：纯黑 + 红色强调，零动画零阴影，低端盒子用。
 *
 * 业务组件禁止写死颜色，一律从 [LocalTvColors] 取。
 */
enum class TvMode { CINEMA, LITE }

data class TvColors(
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentOn: Color,
    val divider: Color,
    val danger: Color,
) {
    companion object {
        /** A 影院模式（UI_SPEC §1.1） */
        val Cinema = TvColors(
            bg = Color(0xFF0A0A0C),
            surface = Color(0xFF141416),
            surfaceHigh = Color(0xFF1C1C1F),
            text = Color(0xFFFFFFFF),
            textSecondary = Color(0xFFCFC7BD),
            textMuted = Color(0xFF8D857C),
            accent = Color(0xFFE8B872),
            accentOn = Color(0xFF1A1206),
            divider = Color(0xFF26262A),
            danger = Color(0xFFE5484D),
        )

        /** C 保命模式（UI_SPEC §1.2） */
        val Lite = TvColors(
            bg = Color(0xFF000000),
            surface = Color(0xFF0D0D0D),
            surfaceHigh = Color(0xFF151515),
            text = Color(0xFFFFFFFF),
            textSecondary = Color(0xFF9A9A9A),
            textMuted = Color(0xFF6B6B6B),
            accent = Color(0xFFFF3B30),
            accentOn = Color(0xFFFFFFFF),
            divider = Color(0xFF1E1E1E),
            danger = Color(0xFFFF3B30),
        )
    }
}

/** 尺寸令牌：字号按 UI_SPEC §1 与 §0.5（baseScale 由调用方按 widthDp 计算后传入）。 */
data class TvDimens(
    val heroTitle: TextUnit,
    val rowTitle: TextUnit,
    val cardTitle: TextUnit,
    val body: TextUnit,
    val caption: TextUnit,
)

val LocalTvColors = staticCompositionLocalOf { TvColors.Cinema }
val LocalTvMode = staticCompositionLocalOf { TvMode.CINEMA }
val LocalTvDimens = staticCompositionLocalOf {
    TvDimens(heroTitle = 43.sp, rowTitle = 14.5.sp, cardTitle = 9.sp, body = 9.5.sp, caption = 7.5.sp)
}

@Composable
fun TvTheme(
    mode: TvMode = TvMode.CINEMA,
    content: @Composable () -> Unit,
) {
    val colors = if (mode == TvMode.CINEMA) TvColors.Cinema else TvColors.Lite
    CompositionLocalProvider(
        LocalTvColors provides colors,
        LocalTvMode provides mode,
    ) {
        Box(modifier = Modifier.fillMaxSize().background(colors.bg)) { content() }
    }
}
