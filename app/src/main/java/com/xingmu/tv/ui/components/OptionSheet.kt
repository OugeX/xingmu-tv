package com.xingmu.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xingmu.tv.ui.LocalTvColors
import com.xingmu.tv.ui.LocalTvDimens

/**
 * t29：筛选浮层（UI_TUNE §1.1「胶囊按钮 + OK 弹浮层，不要下拉框」）。
 *
 * 遥控器友好：
 *  - 选项横向排列成胶囊，焦点默认落在「当前选中」项上；
 *  - OK 立即生效并关闭；BACK/取消 关闭不改；
 *  - 纯色面板 + 1dp 描边，无动画/无阴影（A 模式也不要多余绘制）。
 */
@Composable
fun OptionSheet(
    title: String,
    options: List<String>,
    selected: String?,
    onPick: (String?) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val focus = remember { FocusRequester() }

    LaunchedEffect(title) {
        runCatching { focus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(min = 420.dp, max = 760.dp)
                .background(colors.surface)
                .border(1.dp, colors.divider)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TvLabel(title, dimens.cardTitle, colors.text, weight = FontWeight.SemiBold, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { index, option ->
                    val isSelected = option == selected
                    TvChip(
                        text = option,
                        selected = isSelected,
                        onClick = {
                            onPick(option)
                            onClose()
                        },
                        modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
            Spacer(Modifier.width(1.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("取消", onClick = onClose)
            }
        }
    }
}
