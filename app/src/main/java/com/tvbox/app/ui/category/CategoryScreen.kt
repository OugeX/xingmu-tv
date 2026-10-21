package com.tvbox.app.ui.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvbox.app.ui.ContentGate
import com.tvbox.app.ui.DpadDownSink
import com.tvbox.app.core.AppError
import com.tvbox.app.core.Result
import com.tvbox.app.data.CanonicalCategory
import com.tvbox.app.data.VodSummary
import com.tvbox.app.search.PinyinIndex
import com.tvbox.app.source.SourceRepository
import com.tvbox.app.ui.LocalTvColors
import com.tvbox.app.ui.LocalTvDimens
import com.tvbox.app.ui.TvMode
import com.tvbox.app.ui.components.LiteListRow
import com.tvbox.app.ui.components.LocalListScrolling
import com.tvbox.app.ui.components.PosterCard
import com.tvbox.app.ui.components.TvButton
import com.tvbox.app.ui.components.TvChip
import com.tvbox.app.ui.components.TvEmptyState
import com.tvbox.app.ui.components.TvErrorState
import com.tvbox.app.ui.components.TvLabel
import com.tvbox.app.ui.components.TvLoading
import com.tvbox.app.ui.tvGridColumns
import com.tvbox.app.ui.tvWidthDp
import kotlinx.coroutines.launch

/**
 * 分类网格页（含「海外」一级入口）：
 *  - 子分类 chips：优先用当前页返回的 `vod_class` 去重（MacCMS class 树不在 SourceRepository 契约里，
 *    t10 的完整实现可后续补 class 树；当前用实际条目的子分类做筛选，命中率一致）；
 *  - 排序：最新 / 最热；
 *  - 分页：距末尾 12 项触发下一页，单页 20 条。
 */
@Composable
fun CategoryScreen(
    typeId: Int,
    title: String,
    repo: SourceRepository,
    pinyin: PinyinIndex,
    mode: TvMode,
    onOpenDetail: (VodSummary) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()
    val isOverseasRoot = typeId == CanonicalCategory.OVERSEAS_ROOT

    var selectedTypeId by remember(typeId) {
        mutableIntStateOf(if (isOverseasRoot) CanonicalCategory.overseas.first().id else typeId)
    }
    var selectedTypeName by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(SourceRepository.Sort.LATEST) }
    var items by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var loadMoreFailed by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(selectedTypeId, sort, reloadToken) {
        loading = true
        error = null
        loadMoreFailed = false
        hasMore = true
        page = 1
        selectedTypeName = null
        when (val result = repo.list(selectedTypeId, 1, sort)) {
            is Result.Ok -> {
                items = result.value
                hasMore = result.value.size >= 10
                pinyin.index(result.value)
            }
            is Result.Err -> {
                items = emptyList()
                error = result.error
            }
        }
        loading = false
    }

    val gridState = rememberLazyGridState()
    fun loadMore() {
        if (loading || !hasMore || ContentGate.zapping) return
        loading = true
        loadMoreFailed = false
        scope.launch {
            when (val result = repo.list(selectedTypeId, page + 1, sort)) {
                is Result.Ok -> {
                    val known = items.mapTo(HashSet()) { "${it.sourceId}|${it.vodId}" }
                    val fresh = result.value.filter { "${it.sourceId}|${it.vodId}" !in known }
                    items = items + fresh
                    page += 1
                    hasMore = result.value.size >= 10
                    pinyin.index(fresh)
                }
                is Result.Err -> {
                    loadMoreFailed = true
                    hasMore = false
                }
            }
            loading = false
        }
    }

    val nearEnd by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            items.isNotEmpty() && lastVisible >= items.size - 12
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd) loadMore()
    }

    val subCategories = remember(items) {
        items.mapNotNull { it.typeName.takeIf { name -> name.isNotBlank() } }.distinct().take(10)
    }
    val shown = remember(items, selectedTypeName) {
        val filter = selectedTypeName
        if (filter.isNullOrBlank()) items else items.filter { it.typeName == filter }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvButton("‹ 返回", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            TvLabel(title, dimens.rowTitle, colors.text, weight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(1.dp).weight(1f))
            TvChip("最新", selected = sort == SourceRepository.Sort.LATEST, onClick = { sort = SourceRepository.Sort.LATEST })
            Spacer(Modifier.width(8.dp))
            TvChip("最热", selected = sort == SourceRepository.Sort.HOT, onClick = { sort = SourceRepository.Sort.HOT })
        }

        if (isOverseasRoot) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                items(CanonicalCategory.overseas, key = { it.id }, contentType = { "overseas" }) { section ->
                    TvChip(
                        text = section.label,
                        selected = selectedTypeId == section.id,
                        onClick = { selectedTypeId = section.id },
                    )
                }
            }
        }

        if (subCategories.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") {
                    TvChip("全部", selected = selectedTypeName == null, onClick = { selectedTypeName = null })
                }
                items(subCategories, key = { it }, contentType = { "sub-category" }) { name ->
                    TvChip(name, selected = selectedTypeName == name, onClick = { selectedTypeName = name })
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            when {
                loading && items.isEmpty() -> TvLoading()
                error != null && items.isEmpty() -> TvErrorState(
                    error = error ?: AppError("E_UNKNOWN", "未知错误"),
                    onRetry = { reloadToken++ },
                )
                items.isEmpty() -> TvEmptyState("该分类暂无内容")
                else -> {
                    val columns = tvGridColumns()
                    val contentWidth = tvWidthDp() - (if (mode == TvMode.LITE) 300 else 150) - 96 - 18
                    val cardWidth = ((contentWidth - (columns - 1) * 16) / columns).coerceIn(112, 200).dp
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalListScrolling provides gridState.isScrollInProgress,
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(bottom = 20.dp),
                        ) {
                            items(
                                items = shown,
                                key = { "${it.sourceId}|${it.vodId}" },
                                contentType = { if (mode == TvMode.LITE) "lite-row" else "poster" },
                            ) { vod ->
                                if (mode == TvMode.LITE) {
                                    LiteListRow(vod = vod, onClick = { onOpenDetail(vod) })
                                } else {
                                    PosterCard(vod = vod, onClick = { onOpenDetail(vod) }, width = cardWidth)
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TvLabel(
                text = buildString {
                    append("已加载 ${items.size} 条")
                    if (selectedTypeName != null) append(" · ${selectedTypeName}")
                },
                fontSize = dimens.caption,
                color = colors.textMuted,
                maxLines = 1,
            )
            Spacer(Modifier.width(12.dp))
            if (loading && items.isNotEmpty()) {
                TvLabel("加载中…", dimens.caption, colors.accent, maxLines = 1)
            } else if (loadMoreFailed) {
                TvButton("加载失败，点击重试", onClick = { loadMore() })
            } else if (hasMore && items.isNotEmpty()) {
                TvButton("加载更多", onClick = { loadMore() })
            } else if (items.isNotEmpty()) {
                TvLabel("没有更多了", dimens.caption, colors.textMuted, maxLines = 1)
            }
        }
        DpadDownSink()
    }
}
