package com.xingmu.tv.ui.live

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingmu.tv.live.ChannelSearch
import com.xingmu.tv.live.LiveChannel
import com.xingmu.tv.live.LiveState
import com.xingmu.tv.search.PinyinIndex
import com.xingmu.tv.ui.LocalTvColors
import com.xingmu.tv.ui.LocalTvDimens
import com.xingmu.tv.ui.LocalTvMode
import com.xingmu.tv.ui.LocalTvTier
import com.xingmu.tv.ui.TvMode
import com.xingmu.tv.ui.components.TvFocusable
import com.xingmu.tv.ui.components.TvLabel
import kotlinx.coroutines.delay

/**
 * 直播频道分类面板（t30，docs/LIVE_CATEGORY.md）。
 *
 * 结构：分组 Tab（横向 chips）→（可选）分页签 → 组内两列网格 → 底部提示；
 * 右侧保留当前频道信息卡。**所有分组/分页/过滤都是本面板内部状态，不经 LiveController**，
 * 因此切换分组不会触发播放、也不会重建播放器（验收项）。
 */
@Composable
fun LiveCategoryPanel(
    state: LiveState,
    favorites: Set<String>,
    recents: List<String>,
    pinyin: PinyinIndex,
    onPreview: (LiveChannel) -> Unit,
    onConfirm: (LiveChannel) -> Unit,
    onToggleFavorite: (LiveChannel) -> Unit,
    onDismiss: () -> Unit,
    onToggleShowAll: () -> Unit = {},
    onFilterModeChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val tier = LocalTvTier.current
    val mode = LocalTvMode.current

    val search = remember(context) { ChannelSearch(context) }
    val buckets = remember(state.channels, state.groups, favorites, recents) {
        LiveCategory.buckets(state, favorites, recents)
    }

    var bucketId by remember { mutableStateOf(state.current?.let { cur ->
        buckets.firstOrNull { bucket -> bucket.channels.any { it.id == cur.id } }?.id
    } ?: buckets.firstOrNull { it.channels.isNotEmpty() }?.id ?: buckets.firstOrNull()?.id.orEmpty()) }
    var page by remember { mutableIntStateOf(0) }
    var filterMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 用数组持有焦点下标：只在按键处理里读，避免每次焦点移动都触发整面板重组
    val focusedIndex = remember { intArrayOf(0) }
    // 程序化聚焦（数字直达/搜索结果）不应触发"焦点预览切台"，等用户真正按方向键再解除
    var autoFocusGuard by remember { mutableStateOf(false) }
    // 切分组耗时埋点（验收：≤100ms）：点 chip 的时刻 → 分组生效的时刻
    val switchStart = remember { longArrayOf(0L) }

    // 必须 remember：search.filter 每次调用都返回新列表，若直接做 LaunchedEffect key 会自激重组；
    // key 用 bucketId 而不是 bucket 对象，避免每次重组做 O(n) 的频道列表深比较。
    val bucketChannels = remember(buckets, bucketId) {
        buckets.firstOrNull { it.id == bucketId }?.channels.orEmpty()
    }
    val source = remember(state.channels, bucketChannels, filterMode, query) {
        if (filterMode) search.filter(state.channels, query) else bucketChannels
    }
    val numberFiltered = remember(source, state.numberEntry, filterMode) {
        if (!filterMode) LiveCategory.filterByNumber(source, state.numberEntry) else source
    }
    val pageCount = LiveCategory.pageCount(numberFiltered.size)
    val shown = remember(numberFiltered, page) { LiveCategory.pageOf(numberFiltered, page) }

    val chipFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val firstCellFocus = remember { FocusRequester() }

    val columns = if (tier.isLarge) 1 else 2
    val cellHeight = if (tier.isLarge) 74.dp else if (mode == TvMode.LITE) 54.dp else 58.dp

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { chipFocus.requestFocus() }
    }

    // 过滤态：根节点的数字键直达要让位给输入框（否则在搜索框里打 "5" 会被当成台号）
    LaunchedEffect(filterMode) { onFilterModeChange(filterMode) }

    LaunchedEffect(bucketId) {
        page = 0
        focusedIndex[0] = 0
        if (switchStart[0] > 0L) {
            com.xingmu.tv.core.TvLog.i(
                "LIVE_CAT",
                "group=$bucketId switch_ms=${SystemClock.uptimeMillis() - switchStart[0]} channels=${bucketChannels.size} pages=$pageCount",
            )
            switchStart[0] = 0L
        }
        // 切分组：焦点留在分组 Tab 上（不聚焦频道格 → 不触发预览切台），并确保焦点不丢
        delay(30)
        runCatching { chipFocus.requestFocus() }
    }
    // 数字直达 / 搜台命中：自动滚到第一台并聚焦（不触发预览切台，等用户按方向键才解除）
    LaunchedEffect(numberFiltered, state.numberEntry, filterMode, query) {
        val searching = state.numberEntry.isNotEmpty() || (filterMode && query.isNotBlank())
        if (!searching || numberFiltered.isEmpty()) return@LaunchedEffect
        autoFocusGuard = true
        runCatching { gridState.scrollToItem(0) }
        delay(60)
        runCatching { firstCellFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
        Column(
            modifier = Modifier
                .width(if (columns == 2) 520.dp else 420.dp)
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.92f))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key.nativeKeyCode) {
                        KeyEvent.KEYCODE_BACK -> {
                            if (filterMode) {
                                filterMode = false
                                query = ""
                                true
                            } else {
                                onDismiss()
                                true
                            }
                        }
                        KeyEvent.KEYCODE_MENU -> {
                            // MENU = 快捷搜台（与右侧「搜台」chip 等价）
                            filterMode = true
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                        -> {
                            autoFocusGuard = false
                            false
                        }
                        else -> false
                    }
                },
        ) {
            // ---- 标题行 ----
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TvLabel("频道", dimens.cardTitle, colors.text, weight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                TvLabel(
                    "${state.channels.size} 台 · ${buckets.count { it.channels.isNotEmpty() }} 组",
                    dimens.caption,
                    colors.textMuted,
                )
                Spacer(Modifier.weight(1f))
                if (state.numberEntry.isNotEmpty()) {
                    TvLabel("台号 ${state.numberEntry}", dimens.caption, colors.accent)
                    Spacer(Modifier.width(8.dp))
                }
                if (state.safeMode) {
                    ActionChip(
                        text = if (state.showAllChannels) "仅安全台" else "显示全部",
                        active = state.showAllChannels,
                        enabled = !filterMode,
                    ) { onToggleShowAll() }
                    Spacer(Modifier.width(6.dp))
                }
                SearchChip(active = filterMode) {
                    filterMode = true
                    query = ""
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---- 分组 chips ----
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                items(buckets, key = { it.id }) { item ->
                    val selected = item.id == bucketId && !filterMode
                    val enabled = !filterMode && item.channels.isNotEmpty()
                    GroupChip(
                        text = "${item.name} ${item.channels.size}",
                        selected = selected,
                        enabled = enabled,
                        modifier = if (selected) Modifier.focusRequester(chipFocus) else Modifier,
                        onFocus = {
                            // chips 上按 OK：只切分组，不播放
                            if (item.id != bucketId) {
                                switchStart[0] = SystemClock.uptimeMillis()
                                bucketId = item.id
                                autoFocusGuard = false
                            }
                        },
                    )
                }
            }

            // ---- 过滤态输入框 ----
            if (filterMode) {
                Spacer(Modifier.height(8.dp))
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                TvLabel(
                    "输入台名/首拼（hunan、hnws）· BACK 退出",
                    dimens.caption,
                    colors.textMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // ---- 分页签 ----
            if (!filterMode && pageCount > 1) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (index in 0 until pageCount) {
                        val label = LiveCategory.pageLabel(numberFiltered, index)
                        GroupChip(
                            text = label,
                            selected = index == page,
                            enabled = true,
                            onFocus = {
                                if (page != index) {
                                    page = index
                                    autoFocusGuard = false
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (shown.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TvLabel(
                        when {
                            filterMode && query.isNotBlank() -> "没有匹配的频道：$query"
                            state.numberEntry.isNotEmpty() -> "无此台号：${state.numberEntry}"
                            bucketId == LiveCategory.ID_FAVORITES -> "还没有收藏，长按 OK 收藏当前频道"
                            bucketId == LiveCategory.ID_RECENT -> "还没有看过频道"
                            else -> "这个分组暂无频道"
                        },
                        dimens.caption,
                        colors.textMuted,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key.nativeKeyCode) {
                                // UP 到顶 → 焦点回分组 Tab
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    if (focusedIndex[0] < columns) {
                                        runCatching { chipFocus.requestFocus() }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                // DOWN 到底 → 不越界
                                KeyEvent.KEYCODE_DPAD_DOWN -> focusedIndex[0] + columns >= shown.size
                                // LEFT/RIGHT 在行首/行尾不串到右侧信息卡
                                KeyEvent.KEYCODE_DPAD_LEFT -> focusedIndex[0] % columns == 0
                                KeyEvent.KEYCODE_DPAD_RIGHT ->
                                    focusedIndex[0] % columns == columns - 1 || focusedIndex[0] == shown.lastIndex
                                else -> false
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 8.dp, end = 6.dp),
                ) {
                    itemsIndexed(shown, key = { _, channel -> channel.id }) { index, channel ->
                        ChannelCell(
                            channel = channel,
                            selected = channel.id == state.current?.id,
                            favorite = channel.id in favorites,
                            tag = LiveCategory.tagOf(channel, state.showAllChannels),
                            height = cellHeight,
                            modifier = if (index == 0) Modifier.focusRequester(firstCellFocus) else Modifier,
                            onFocused = {
                                focusedIndex[0] = index
                                if (!autoFocusGuard) onPreview(channel)
                            },
                            onClick = { onConfirm(channel) },
                            onLongPress = { onToggleFavorite(channel) },
                        )
                    }
                }
            }

            TvLabel(
                "数字键直达 · 长按 OK 收藏 · MENU 搜台 · LEFT/BACK 收起",
                dimens.caption,
                colors.textMuted,
                maxLines = 1,
            )
        }

        // ---- 右侧信息卡（保持原有信息密度）----
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .width(390.dp)
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(18.dp),
        ) {
            val channel = state.current
            TvLabel(channel?.name ?: "未选择频道", dimens.rowTitle, colors.text, weight = FontWeight.Bold, maxLines = 1)
            TvLabel(
                channel?.let { "${it.groupName} · 台号 ${it.number} · ${it.health.label}" }.orEmpty(),
                dimens.caption,
                colors.textSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            TvLabel(state.statusText, dimens.caption, colors.textMuted, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            TvLabel(state.epg.text, dimens.caption, colors.textSecondary, maxLines = 3)
            channel?.desc?.let {
                Spacer(Modifier.height(6.dp))
                TvLabel(it, dimens.caption, colors.textMuted, maxLines = 3)
            }
        }
    }
}

@Composable
private fun GroupChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val shape = RoundedCornerShape(6.dp)
    TvFocusable(
        modifier = modifier
            .height(38.dp)
            .onFocusChanged { if (it.isFocused && enabled) onFocus() },
        shape = shape,
        enabled = enabled,
        onClick = onFocus,
    ) { focused ->
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(
                    when {
                        !enabled -> colors.surface.copy(alpha = 0.35f)
                        selected -> colors.accent.copy(alpha = 0.22f)
                        focused -> colors.surfaceHigh
                        else -> colors.surface
                    },
                    shape,
                )
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) colors.accent else Color.Transparent,
                    shape,
                )
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            TvLabel(
                text = text,
                fontSize = dimens.caption,
                color = when {
                    !enabled -> colors.textMuted.copy(alpha = 0.5f)
                    selected -> colors.accent
                    else -> colors.textSecondary
                },
                weight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ActionChip(
    text: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val shape = RoundedCornerShape(6.dp)
    TvFocusable(
        modifier = Modifier.height(38.dp),
        shape = shape,
        enabled = enabled,
        onClick = onClick,
    ) { focused ->
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(
                    when {
                        !enabled -> colors.surface.copy(alpha = 0.35f)
                        active || focused -> colors.surfaceHigh
                        else -> colors.surface
                    },
                    shape,
                )
                .border(1.dp, if (active) colors.accent else Color.Transparent, shape)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            TvLabel(
                text = text,
                fontSize = dimens.caption,
                color = when {
                    !enabled -> colors.textMuted.copy(alpha = 0.5f)
                    active -> colors.accent
                    else -> colors.textSecondary
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SearchChip(
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val shape = RoundedCornerShape(6.dp)
    TvFocusable(
        modifier = Modifier.height(38.dp),
        shape = shape,
        onClick = onClick,
    ) { focused ->
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(if (active || focused) colors.surfaceHigh else colors.surface, shape)
                .border(1.dp, if (active) colors.accent else Color.Transparent, shape)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            TvLabel("搜台", dimens.caption, if (active) colors.accent else colors.textSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { requester.requestFocus() }
    }
    Box(
        modifier = modifier
            .height(40.dp)
            .background(colors.surfaceHigh, RoundedCornerShape(6.dp))
            .border(1.dp, colors.accent, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.text, fontSize = dimens.caption),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    TvLabel("搜台：输入台名或首拼", dimens.caption, colors.textMuted, maxLines = 1)
                }
                inner()
            },
        )
    }
}

@Composable
private fun ChannelCell(
    channel: LiveChannel,
    selected: Boolean,
    favorite: Boolean,
    tag: ChannelTag?,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val shape = RoundedCornerShape(5.dp)
    var downAt by remember { mutableLongStateOf(0L) }
    TvFocusable(
        modifier = modifier
            .height(height)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                val code = event.key.nativeKeyCode
                val select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                if (!select) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        downAt = SystemClock.uptimeMillis()
                        false
                    }
                    KeyEventType.KeyUp -> {
                        // 长按 ≥600ms = 收藏/取消收藏（短按仍是播放）
                        if (SystemClock.uptimeMillis() - downAt >= 600L) {
                            onLongPress()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
        shape = shape,
        onClick = onClick,
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    when {
                        selected -> colors.accent.copy(alpha = 0.18f)
                        focused -> colors.surfaceHigh
                        else -> colors.surface.copy(alpha = 0.55f)
                    },
                    shape,
                )
                .border(
                    if (selected || focused) 1.dp else 0.dp,
                    if (focused) colors.accent else if (selected) colors.accent.copy(alpha = 0.5f) else Color.Transparent,
                    shape,
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvLabel(
                text = "%03d".format(channel.number),
                fontSize = dimens.caption,
                color = if (selected || focused) colors.accent else colors.textMuted,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                TvLabel(
                    text = (if (favorite) "★ " else "") + channel.name,
                    fontSize = dimens.caption,
                    color = if (selected) colors.text else colors.textSecondary,
                    weight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
            tag?.let {
                TvLabel(
                    text = it.text,
                    fontSize = dimens.caption,
                    color = if (it.danger) colors.danger else colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}
