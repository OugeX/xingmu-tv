package com.xingmu.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingmu.tv.core.AppError
import com.xingmu.tv.ui.LocalTvColors
import com.xingmu.tv.ui.LocalTvDimens
import com.xingmu.tv.ui.LocalTvMode
import com.xingmu.tv.ui.LocalTvTier
import com.xingmu.tv.ui.TvMode

/** 判断遥控器“确认”键（OK / ENTER / 小键盘回车）。 */
fun isSelectKey(key: Key): Boolean =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * 可聚焦容器（D-pad 基础单元）：
 *  - 焦点态 = 3dp 主色描边（A 模式额外 1.04 倍缩放，C 模式不缩放）；
 *  - OK 键在 KeyUp 触发，避免长按连发；
 *  - 不使用 ripple / 阴影 / 动画（低端 GPU 友好）。
 */
@Composable
fun TvFocusable(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    borderWidth: Dp = if (LocalTvTier.current.isLarge) 5.dp else 3.dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val colors = LocalTvColors.current
    val mode = LocalTvMode.current
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .then(
                if (focused) {
                    Modifier
                        .border(borderWidth, colors.accent, shape)
                        .then(if (mode == TvMode.CINEMA) Modifier.scale(1.04f) else Modifier)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled)
            .onKeyEvent { event ->
                if (enabled && event.type == KeyEventType.KeyUp && isSelectKey(event.key)) {
                    onClick()
                    true
                } else {
                    false
                }
            },
        content = { content(focused) },
    )
}

@Composable
fun TvLabel(
    text: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = weight,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = letterSpacing,
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
    )
}

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
    compact: Boolean = false,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val lite = LocalTvMode.current == TvMode.LITE
    val largeText = LocalTvTier.current.isLarge
    val shape = if (lite) RectangleShape else androidx.compose.foundation.shape.RoundedCornerShape(6.dp)

    TvFocusable(
        modifier = modifier,
        shape = shape,
        enabled = enabled,
        onClick = onClick,
    ) { focused ->
        val background = when {
            !enabled -> colors.surfaceHigh
            primary -> if (focused) colors.accent.copy(alpha = 0.88f) else colors.accent
            selected -> colors.surfaceHigh
            focused -> colors.surfaceHigh
            else -> colors.surface
        }
        val foreground = when {
            !enabled -> colors.textMuted
            primary -> colors.accentOn
            focused || selected -> colors.text
            else -> colors.textSecondary
        }
        Box(
            modifier = Modifier
                .background(background, shape)
                .then(if (largeText && !compact) Modifier.height(64.dp) else Modifier)
                .padding(
                    horizontal = if (largeText && !compact) 20.dp else 16.dp,
                    vertical = if (compact) 2.dp else 9.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            TvLabel(
                text = text,
                fontSize = dimens.caption,
                color = foreground,
                weight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun TvChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        selected = selected,
        primary = false,
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvLabel(title, dimens.rowTitle, colors.text, weight = FontWeight.Bold, maxLines = 1)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.width(12.dp))
            TvLabel(subtitle, dimens.caption, colors.textMuted, maxLines = 1)
        }
        Spacer(Modifier.width(1.dp).weight(1f))
        if (actionText != null && onAction != null) {
            TvButton(text = actionText, onClick = onAction)
        }
    }
}

@Composable
fun TvLoading(message: String = "加载中…", modifier: Modifier = Modifier) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        TvLabel(message, dimens.body, colors.textMuted)
    }
}

/** A 模式的骨架屏：静态灰块，不做无限 shimmer（低端机避免持续重绘）。 */
@Composable
fun TvSkeletonRows(rows: Int = 3, columns: Int = 6, modifier: Modifier = Modifier) {
    val colors = LocalTvColors.current
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        repeat(rows) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                repeat(columns) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(190.dp)
                            .background(colors.surfaceHigh),
                    )
                }
            }
        }
    }
}

@Composable
fun TvErrorState(
    error: AppError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    extraActionText: String? = null,
    onExtraAction: (() -> Unit)? = null,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvLabel(error.display(), dimens.body, colors.danger, maxLines = 3)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("重试", onClick = onRetry, primary = true)
            if (extraActionText != null && onExtraAction != null) {
                TvButton(extraActionText, onClick = onExtraAction)
            }
        }
    }
}

@Composable
fun TvEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvLabel(message, dimens.body, colors.textMuted, maxLines = 3)
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            TvButton(actionText, onClick = onAction)
        }
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    val colors = LocalTvColors.current
    Box(modifier.fillMaxWidth().height(1.dp).background(colors.divider))
}

@Composable
fun StatusDot(active: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalTvColors.current
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (active) colors.accent else colors.textMuted),
    )
}

/** 给文本画一个底色块（错误码/角标）。 */
@Composable
fun Tag(text: String, color: Color, textColor: Color) {
    val dimens = LocalTvDimens.current
    Box(
        modifier = Modifier
            .background(SolidColor(color), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        TvLabel(text, dimens.caption, textColor, maxLines = 1, weight = FontWeight.Medium)
    }
}
