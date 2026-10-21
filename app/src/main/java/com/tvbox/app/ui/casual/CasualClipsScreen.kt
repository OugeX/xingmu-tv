package com.tvbox.app.ui.casual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvbox.app.ui.ContentGate
import com.tvbox.app.core.AppError
import com.tvbox.app.core.Result
import com.tvbox.app.data.casual.CasualRepository
import com.tvbox.app.data.casual.CasualTab
import com.tvbox.app.data.casual.ShortClip
import com.tvbox.app.ui.LocalTvColors
import com.tvbox.app.ui.LocalTvDimens
import com.tvbox.app.ui.LocalTvMode
import com.tvbox.app.ui.LocalTvTier
import com.tvbox.app.ui.TvMode
import com.tvbox.app.ui.components.PosterImage
import com.tvbox.app.ui.components.TvButton
import com.tvbox.app.ui.components.TvChip
import com.tvbox.app.ui.components.TvEmptyState
import com.tvbox.app.ui.components.TvErrorState
import com.tvbox.app.ui.components.TvFocusable
import com.tvbox.app.ui.components.TvLabel
import com.tvbox.app.ui.components.TvLoading
import com.tvbox.app.ui.tvWidthDp
import kotlinx.coroutines.launch

/**
 * 看页（docs/CASUAL_SPEC.md §2.2）：
 * 分段 Tab（推荐/音乐/动画/游戏/知识/生活），常规 4 列 / 大字号档 3 列，
 * 16:9 卡片（时长角标/UP 主/播放量/标题 2 行），OK 全屏播放，滚动加载 20 条/页。
 */
@Composable
fun CasualClipsScreen(
    repo: CasualRepository,
    onPlayVideo: (List<ShortClip>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val mode = LocalTvMode.current
    val tier = LocalTvTier.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(CasualTab.RECOMMEND) }
    var items by remember { mutableStateOf<List<ShortClip>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var offlineHint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tab, reload) {
        loading = true
        error = null
        offlineHint = null
        page = 1
        hasMore = true
        when (val result = repo.clips(tab, 1)) {
            is Result.Ok -> {
                items = result.value
                hasMore = result.value.size >= 15
                offlineHint = if (result.value.isEmpty()) "接口暂无数据，稍后再试" else null
            }
            is Result.Err -> {
                items = emptyList()
                error = result.error
            }
        }
        loading = false
    }

    fun loadMore() {
        if (loading || loadingMore || !hasMore || ContentGate.zapping) return
        loadingMore = true
        scope.launch {
            when (val result = repo.clips(tab, page + 1)) {
                is Result.Ok -> {
                    val known = items.mapTo(HashSet()) { it.bvid }
                    val fresh = result.value.filter { it.bvid !in known }
                    items = items + fresh
                    page += 1
                    hasMore = result.value.size >= 15 && fresh.isNotEmpty()
                }
                is Result.Err -> hasMore = false
            }
            loadingMore = false
        }
    }

    val columns = if (tier.isLarge) 3 else 4
    val navWidth = when {
        mode == TvMode.LITE -> 300
        tier == com.tvbox.app.ui.FontTier.LARGE -> 190
        tier == com.tvbox.app.ui.FontTier.XLARGE -> 220
        else -> 150
    }
    val contentWidth = tvWidthDp() - navWidth - 96 - 18
    val cardWidth = ((contentWidth - (columns - 1) * 14) / columns).dp
    val gridState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            items.isNotEmpty() && last >= items.size - 8
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd) loadMore()
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvLabel("随便看", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            TvLabel("B 站公开榜单 · 未登录 360/480 档", dimens.caption, colors.textMuted)
            Spacer(Modifier.width(1.dp).weight(1f))
            if (loadingMore) TvLabel("加载中…", dimens.caption, colors.accent)
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CasualTab.entries, key = { it.name }) { target ->
                TvChip(
                    text = target.label,
                    selected = tab == target,
                    onClick = { tab = target },
                )
            }
        }
        offlineHint?.let {
            Spacer(Modifier.height(4.dp))
            TvLabel(it, dimens.caption, colors.textMuted, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f)) {
            when {
                loading && items.isEmpty() -> TvLoading("正在拉取 ${tab.label}…")
                error != null && items.isEmpty() -> TvErrorState(
                    error = error ?: AppError("E_UNKNOWN", "加载失败"),
                    onRetry = { reload++ },
                    extraActionText = "换个 Tab",
                    onExtraAction = { tab = CasualTab.RECOMMEND },
                )
                items.isEmpty() -> TvEmptyState("暂无视频，稍后再试")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(items, key = { it.bvid }) { clip ->
                        ClipCard(
                            clip = clip,
                            width = cardWidth,
                            onClick = { onPlayVideo(items, items.indexOf(clip)) },
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TvLabel(
                "已加载 ${items.size} 条 · ${tab.label}",
                dimens.caption,
                colors.textMuted,
                maxLines = 1,
            )
            Spacer(Modifier.width(1.dp).weight(1f))
            if (hasMore && items.isNotEmpty()) {
                TvButton("加载更多", onClick = { loadMore() })
            } else if (items.isNotEmpty()) {
                TvLabel("没有更多了", dimens.caption, colors.textMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ClipCard(
    clip: ShortClip,
    width: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val lite = LocalTvMode.current == TvMode.LITE
    val shape = if (lite) RoundedCornerShape(0.dp) else RoundedCornerShape(8.dp)
    TvFocusable(
        modifier = Modifier.width(width),
        shape = shape,
        onClick = onClick,
    ) { focused ->
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(shape),
            ) {
                PosterImage(
                    url = clip.pic,
                    modifier = Modifier.fillMaxSize(),
                    requestWidth = 320,
                    requestHeight = 180,
                )
                if (clip.durationSec > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        TvLabel(
                            formatDuration(clip.durationSec),
                            dimens.caption,
                            Color.White,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TvLabel(
                text = clip.title,
                fontSize = dimens.cardTitle,
                color = if (focused) colors.accent else colors.text,
                weight = FontWeight.Medium,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TvLabel(
                    text = clip.owner,
                    fontSize = dimens.caption,
                    color = colors.textMuted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TvLabel(
                    text = formatViews(clip.views),
                    fontSize = dimens.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

internal fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "--:--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

internal fun formatViews(views: Long): String = when {
    views >= 100_000_000 -> "%.1f亿".format(views / 100_000_000.0)
    views >= 10_000 -> "%.1f万".format(views / 10_000.0)
    else -> views.toString()
}
