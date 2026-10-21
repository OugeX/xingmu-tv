package com.tvbox.app.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 直播频道浮层（UI_SPEC §3.6）：LEFT/MENU 呼出的半透明浮层，**视频继续播、不暂停、不缩放**。
 *
 * - 左侧 320dp：分组可折叠 + 台号/台名/线路健康度；DPAD 上下移动触发 [onFocusChannel]
 *   （LiveController.preview，800ms 防抖），OK/点击触发 [onPick] 立即切台并收起；
 * - 右侧：频道信息卡（台标占位/分组/实时分辨率码率编码/备用线路数/起播延迟/简介/EPG 进度），
 *   与左侧焦点联动（state.current 变化即时更新）；
 * - C 模式：纯文字，无缩略图、无动画。
 */
@Composable
fun LiveChannelOverlay(
    state: LiveState,
    onFocusChannel: (LiveChannel) -> Unit,
    onPick: (LiveChannel) -> Unit,
    onSelectLine: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.overlay == LiveOverlay.NONE) return

    var collapsedGroups by remember { mutableStateOf(setOf<String>()) }
    val rows = remember(state.groups, state.channels, state.groupedChannels, collapsedGroups) {
        buildRows(state, collapsedGroups)
    }
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val currentIndex = remember(rows) {
        rows.indexOfFirst { it is OverlayRow.ChannelRow && it.channel.id == state.current?.id }
    }

    LaunchedEffect(state.overlay, currentIndex) {
        if (currentIndex >= 0) {
            runCatching { listState.scrollToItem(currentIndex) }
            delay(60)
            runCatching { currentFocus.requestFocus() }
        }
    }

    Row(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.82f))
                .padding(vertical = 16.dp),
        ) {
            BasicText(
                text = "直播频道 · ${state.channels.size}",
                style = TextStyle(color = Color(0xFFFFC107), fontSize = 20.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            state.safeFilterHint?.let { hint ->
                BasicText(
                    text = hint,
                    style = TextStyle(color = Color(0xFFFFC107), fontSize = 13.sp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(rows.size) { index ->
                    when (val row = rows[index]) {
                        is OverlayRow.Header -> OverlayGroupHeader(row) {
                            collapsedGroups = if (row.group.id in collapsedGroups) {
                                collapsedGroups - row.group.id
                            } else {
                                collapsedGroups + row.group.id
                            }
                        }
                        is OverlayRow.ChannelRow -> {
                            val selected = row.channel.id == state.current?.id
                            OverlayChannelRow(
                                channel = row.channel,
                                selected = selected,
                                focusRequester = if (selected) currentFocus else null,
                                onFocused = { onFocusChannel(row.channel) },
                                onClick = { onPick(row.channel) },
                            )
                        }
                    }
                }
            }
            BasicText(
                text = "OK 确认并收起 · LEFT/BACK 收起",
                style = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        ChannelInfoCard(
            info = state.channelInfo,
            epg = state.epg,
            onSelectLine = onSelectLine,
            modifier = Modifier.width(360.dp).fillMaxHeight(),
        )
        Spacer(Modifier.weight(1f).clickable { onDismiss() })
    }
}

private sealed interface OverlayRow {
    data class Header(val group: LiveGroup, val expanded: Boolean, val count: Int) : OverlayRow
    data class ChannelRow(val channel: LiveChannel) : OverlayRow
}

private fun buildRows(state: LiveState, collapsed: Set<String>): List<OverlayRow> {
    val rows = ArrayList<OverlayRow>(state.channels.size + state.groups.size)
    for (group in state.groups) {
        val channels = state.groupedChannels[group.name] ?: state.channels.filter { it.groupId == group.id }
        rows += OverlayRow.Header(group, group.id !in collapsed, channels.size)
        if (group.id !in collapsed) {
            channels.forEach { rows += OverlayRow.ChannelRow(it) }
        }
    }
    // 种子/导入里出现但未在 groups 声明的频道，兜底到「其他」
    val known = state.groups.map { it.name }.toSet()
    val others = state.channels.filter { it.groupName !in known }
    if (others.isNotEmpty()) {
        rows += OverlayRow.Header(LiveGroup("other", "其他", 99, others.size), "other" !in collapsed, others.size)
        if ("other" !in collapsed) others.forEach { rows += OverlayRow.ChannelRow(it) }
    }
    return rows
}

@Composable
private fun OverlayGroupHeader(header: OverlayRow.Header, onToggle: () -> Unit) {
    BasicText(
        text = "${if (header.expanded) "▾" else "▸"} ${header.group.name}（${header.count}）",
        style = TextStyle(color = Color(0xFFFFC107), fontSize = 17.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .focusable()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun OverlayChannelRow(
    channel: LiveChannel,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val accent = Color(0xFFFFC107)
    var focused by remember { mutableStateOf(false) }
    val base = if (selected) {
        Modifier.background(accent.copy(alpha = 0.18f))
    } else if (focused) {
        Modifier.background(Color.White.copy(alpha = 0.10f))
    } else {
        Modifier
    }
    BasicText(
        text = buildString {
            append(channel.numberText).append("  ").append(channel.name)
            if (channel.hdZone) append("  HD")
            if (channel.safe == false) append("  可能黑屏")
            if (channel.health.unstable) append("  · ").append(channel.health.label)
            append("  ").append(channel.bestLabel)
            channel.bestLine?.let { line ->
                if (line.bitrateBps > 0) append(" · ").append(line.bitrateText)
            }
        },
        style = TextStyle(
            color = if (selected) accent else Color.White,
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(onClick = onClick)
            .then(base)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

/** 频道信息卡：台标占位、分组、实时参数、备用线路、简介、EPG（正在播/下一档 + 进度）。 */
@Composable
fun ChannelInfoCard(
    info: LiveChannelInfo?,
    epg: EpgNowNext,
    onSelectLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(20.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        if (info == null) {
            BasicText("暂无频道信息", style = TextStyle(color = Color.White, fontSize = 18.sp))
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = info.channelName.take(1),
                    style = TextStyle(color = Color(0xFFFFC107), fontSize = 30.sp, fontWeight = FontWeight.Bold),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                BasicText(
                    text = info.channelName,
                    style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                )
                BasicText(
                    text = "台号 ${info.channel.numberText} · ${info.groupName} · ${info.channel.health.label}",
                    style = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        InfoLine("实时参数", info.measuredText)
        InfoLine("档位", "${info.channel.bestLabel} · 4K ${if (info.channel.lines.any { it.is4k }) "有" else "无"}")
        InfoLine("线路", "${info.lineText} · 备用 ${info.channel.lines.count { it.fallbackOnly }} 条 · 延迟 ${info.lineLatencyText}")
        InfoLine("简介", info.descText.take(80))
        Spacer(Modifier.height(12.dp))
        BasicText(
            text = "正在播 / 下一档",
            style = TextStyle(color = Color(0xFFFFC107), fontSize = 15.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.height(6.dp))
        InfoLine("正在播", epg.nowTitle)
        InfoLine("下一档", epg.nextTitle)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.18f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(epg.progress(System.currentTimeMillis()))
                    .height(4.dp)
                    .background(Color(0xFFFFC107)),
            )
        }
        Spacer(Modifier.height(16.dp))
        BasicText(
            text = "线路选择（OK 切换，保留进度）",
            style = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp),
        )
        info.channel.lines.forEach { line ->
            val selected = line.index == info.lineIndex
            val fourK = line.is4k
            BasicText(
                text = (if (selected) "● " else "○ ") + line.label +
                    (if (fourK) " [4K]" else "") +
                    (if (line.bitrateBps > 0) " · ${line.bitrateText}" else ""),
                style = TextStyle(
                    color = if (selected) Color(0xFFFFC107) else Color.White,
                    fontSize = 15.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusable()
                    .clickable { onSelectLine(line.index) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/** showAll 下选中仅 1080p 频道的确认框（t4 在 LiveScreen 根部按 state.pendingUnsafe 渲染）。 */
@Composable
fun UnsafeChannelConfirmDialog(
    channel: LiveChannel?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (channel == null) return
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF202020))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = channel.name,
                style = TextStyle(color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = "该频道本机可能无法显示（仅 1080p 源），仍要尝试？",
                style = TextStyle(color = Color(0xFFFFC107), fontSize = 16.sp),
            )
            Spacer(Modifier.height(18.dp))
            Row {
                HudButton("仍要尝试") { onConfirm() }
                Spacer(Modifier.width(12.dp))
                HudButton("取消") { onCancel() }
            }
        }
    }
}

@Composable
private fun HudButton(text: String, onClick: () -> Unit) {
    BasicText(
        text = text,
        style = TextStyle(color = Color.White, fontSize = 18.sp),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

@Composable
private fun InfoLine(label: String, value: String) {    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        BasicText(
            text = label,
            style = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp),
            modifier = Modifier.width(72.dp),
        )
        BasicText(
            text = value,
            style = TextStyle(color = Color.White, fontSize = 14.sp),
            modifier = Modifier.weight(1f),
        )
    }
}
