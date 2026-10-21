package com.tvbox.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 屏幕适配（docs/UI_SPEC.md §0.5）：
 *  - 基线 960dp × 540dp；
 *  - 字号 = 基线字号 × clamp(widthDp / 960, .85, 1.15) × fontScale(clamp .85–1.3)；
 *  - Hero 高度 = 屏高 × 0.59，clamp 240–640dp；
 *  - 列数：<800 4 列 / 800–1199 5 列 / ≥1200 6 列；C 模式 2 列。
 */
@Composable
@ReadOnlyComposable
fun tvBaseScale(): Float = baseScaleFor(LocalConfiguration.current.screenWidthDp)

fun baseScaleFor(widthDp: Int): Float = (widthDp / 960f).coerceIn(0.85f, 1.15f)

@Composable
@ReadOnlyComposable
fun tvGridColumns(): Int = gridColumnsFor(
    widthDp = LocalConfiguration.current.screenWidthDp,
    mode = LocalTvMode.current,
    tier = LocalTvTier.current,
)

fun gridColumnsFor(widthDp: Int, mode: TvMode, tier: FontTier): Int {
    if (tier.isLarge) return tier.columns
    return if (mode == TvMode.LITE) 2 else when {
        widthDp < 800 -> 4
        widthDp < 1200 -> 5
        else -> 6
    }
}

@Composable
@ReadOnlyComposable
fun tvHeroHeight(): Dp = (LocalConfiguration.current.screenHeightDp * 0.59f).coerceIn(240f, 640f).dp

/** 过度扫描安全区（默认左右 48dp / 上下 27dp，可在设置里调，见 UI_SPEC §0.5）。 */
val OverscanHorizontal: Dp = 48.dp
val OverscanVertical: Dp = 27.dp

@Composable
fun tvDimens(): TvDimens {
    val mode = LocalTvMode.current
    val tier = LocalTvTier.current
    val widthScale = tvBaseScale()
    val k = widthScale * tier.scale
    fun scaled(base: Float): TextUnit = (base * k).sp
    if (mode == TvMode.LITE) {
        // C 模式不叠加系统 fontScale；大字号档同样放大，辅助信息底线 15sp
        return TvDimens(
            heroTitle = scaled(40f),
            rowTitle = scaled(26f),
            cardTitle = scaled(20f),
            body = scaled(18f),
            caption = maxOf(scaled(14f).value, 15f).sp,
        )
    }
    return TvDimens(
        heroTitle = scaled(86f),
        rowTitle = scaled(29f),
        cardTitle = scaled(18f),
        body = scaled(19f),
        caption = maxOf(scaled(15f).value, 15f).sp,
    )
}

@Composable
@ReadOnlyComposable
fun tvWidthDp(): Int = LocalConfiguration.current.screenWidthDp

@Composable
@ReadOnlyComposable
fun shouldForceLiteMode(): Boolean {
    val config = LocalConfiguration.current
    return config.smallestScreenWidthDp < 600 || config.screenHeightDp < 600
}

/** 根据屏宽算一屏海报卡片的宽度（6 列时约 150dp）。 */
@Composable
@ReadOnlyComposable
fun tvPosterWidth(): Dp {
    val columns = tvGridColumns()
    val available = tvWidthDp() - 2 * OverscanHorizontal.value - (columns - 1) * 16f
    val width = (available / columns).coerceIn(112f, 190f)
    return width.dp
}

internal fun Int.coerceAtLeastZero(): Int = if (this < 0) 0 else this

internal fun Float.toPxInt(): Int = roundToInt()
