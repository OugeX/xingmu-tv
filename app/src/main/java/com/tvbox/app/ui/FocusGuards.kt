package com.tvbox.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * 焦点下边界哨兵（t18 R3）：放在每个页面/浮层内容末尾。
 * 焦点落到底部后继续按 DOWN 会被这里消费，不再穿透到系统桌面/Launcher。
 * 1dp 高度、透明；按 UP 可回到上方最后一个可聚焦元素。
 */
@Composable
fun DpadDownSink(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .focusable()
            .onKeyEvent { event ->
                event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionDown || event.key == Key.PageDown)
            },
    )
}
