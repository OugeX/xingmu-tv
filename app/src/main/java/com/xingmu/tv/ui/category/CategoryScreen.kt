package com.xingmu.tv.ui.category

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xingmu.tv.core.AppError
import com.xingmu.tv.core.Result
import com.xingmu.tv.data.CanonicalCategory
import com.xingmu.tv.data.VodSummary
import com.xingmu.tv.search.PinyinIndex
import com.xingmu.tv.source.SourceRepository
import com.xingmu.tv.ui.ContentGate
import com.xingmu.tv.ui.DpadDownSink
import com.xingmu.tv.ui.LocalTvColors
import com.xingmu.tv.ui.LocalTvDimens
import com.xingmu.tv.ui.TvMode
import com.xingmu.tv.ui.components.LiteListRow
import com.xingmu.tv.ui.components.LocalListScrolling
import com.xingmu.tv.ui.components.OptionSheet
import com.xingmu.tv.ui.components.PosterCard
import com.xingmu.tv.ui.components.SkeletonPosterCard
import com.xingmu.tv.ui.components.TvButton
import com.xingmu.tv.ui.components.TvChip
import com.xingmu.tv.ui.components.TvEmptyState
import com.xingmu.tv.ui.components.TvErrorState
import com.xingmu.tv.ui.components.TvFocusable
import com.xingmu.tv.ui.components.TvLabel
import com.xingmu.tv.ui.tvGridColumns
import com.xingmu.tv.ui.tvWidthDp
import kotlinx.coroutines.launch

/**
 * 分类页（t29 三层结构，UI_TUNE §1）：
 *
 *   一级 Tab（电影/剧集/动漫/综艺/海外/短剧）
 *   └ 二级子分类 chips（全部 / 动作 / 喜剧 …）
 *     └ 筛选（年份 / 地区 / 排序）：胶囊按钮 + OK 弹浮层（不做下拉框）
 *       └ 面包屑（电影 › 动作片 · 2026 · 大陆 · 最新）＋ 已加载 N 条
 *
 * 说明：
 *  - 年份/地区筛选对**已加载页**做客户端过滤（SourceRepository 契约暂无年份/地区参数），
 *    面包屑同时展示当前组合，加载更多后过滤结果自动扩展；
 *  - 「评分」排序同样是客户端排序，服务端只支持 最新/最热；
 *  - 滚动位置由外部传入的 [gridState] 持有（AppRoot 缓存），从详情返回时不跳回顶部。
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
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()
    val lite = mode == TvMode.LITE

    var primaryTab by remember(typeId) { mutableIntStateOf(typeId) }
    var overseasSection by remember { mutableIntStateOf(CanonicalCategory.overseas.first().id) }
    var selectedTypeName by remember(primaryTab) { mutableStateOf<String?>(null) }
    var yearFilter by remember(primaryTab) { mutableStateOf<String?>(null) }
    var areaFilter by remember(primaryTab) { mutableStateOf<String?>(null) }
    var sort by remember(primaryTab) { mutableStateOf(SourceRepository.Sort.LATEST) }
    var sortByScore by remember(primaryTab) { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<FilterKind?>(null) }

    val effectiveTypeId = if (primaryTab == CanonicalCategory.OVERSEAS_ROOT) overseasSection else primaryTab

    var items by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var loadMoreFailed by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(effectiveTypeId, sort, reloadToken) {
        loading = true
        error = null
        loadMoreFailed = false
        hasMore = true
        page = 1
        when (val result = repo.list(effectiveTypeId, 1, sort)) {
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

    fun loadMore() {
        if (loading || !hasMore || ContentGate.zapping) return
        loading = true
        loadMoreFailed = false
        scope.launch {
            when (val result = repo.list(effectiveTypeId, page + 1, sort)) {
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
            items.isNotEmpty() && lastVisible >= items.size - 8
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd) loadMore()
    }

    val subCategories = remember(items) {
        items.mapNotNull { it.typeName.takeIf { name -> name.isNotBlank() } }.distinct().take(12)
    }

    val shown = remember(items, selectedTypeName, yearFilter, areaFilter, sortByScore) {
        val filtered = items.filter { vod ->
            (selectedTypeName == null || vod.typeName == selectedTypeName) &&
                (yearFilter == null || vod.year == yearFilter) &&
                (areaFilter == null || areaMatches(vod.area, areaFilter!!))
        }
        if (sortByScore) filtered.sortedByDescending { it.score.toDoubleOrNull() ?: 0.0 } else filtered
    }

    val rootLabel = CanonicalCategory.labelOf(primaryTab) ?: title
    val breadcrumb = remember(rootLabel, effectiveTypeId, selectedTypeName, yearFilter, areaFilter, sort, sortByScore) {
        buildString {
            append(rootLabel)
            if (primaryTab == CanonicalCategory.OVERSEAS_ROOT) {
                CanonicalCategory.overseas.firstOrNull { it.id == effectiveTypeId }?.let {
                    append(" › ")
                    append(it.label)
                }
            }
            if (selectedTypeName != null) {
                append(" › ")
                append(selectedTypeName)
            }
            val filters = listOfNotNull(yearFilter, areaFilter, sortLabel(sort, sortByScore))
            if (filters.isNotEmpty()) append(" · ").append(filters.joinToString(" · "))
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ── 顶部：返回 + 面包屑 ──────────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvButton("‹ 返回", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            TvLabel(breadcrumb, dimens.body, colors.text, weight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(1.dp).weight(1f))
            TvLabel("已加载 ${items.size} 条", dimens.caption, colors.textMuted, maxLines = 1)
        }

        // ── 一级 Tab（第 1 层）──────────────────────────────────────────────
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            items(CanonicalCategory.primaryTabs, key = { it.typeId }, contentType = { "primary-tab" }) { tab ->
                if (lite) {
                    LitePill(
                        text = tab.label,
                        selected = primaryTab == tab.typeId,
                        onClick = { primaryTab = tab.typeId },
                    )
                } else {
                    TvChip(
                        text = tab.label,
                        selected = primaryTab == tab.typeId,
                        onClick = { primaryTab = tab.typeId },
                    )
                }
            }
        }

        // 海外：二级分区（韩剧/美剧/日剧…）
        if (primaryTab == CanonicalCategory.OVERSEAS_ROOT) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CanonicalCategory.overseas, key = { it.id }, contentType = { "overseas" }) { section ->
                    TvChip(
                        text = section.label,
                        selected = overseasSection == section.id,
                        onClick = { overseasSection = section.id },
                    )
                }
            }
        }

        // ── 二级子分类（第 2 层）────────────────────────────────────────────
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

        // ── 筛选（第 3 层）：胶囊 + OK 浮层 ─────────────────────────────────
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TvChip(
                text = "年份：${yearFilter ?: "全部"}",
                selected = yearFilter != null,
                onClick = { sheet = FilterKind.YEAR },
            )
            TvChip(
                text = "地区：${areaFilter ?: "全部"}",
                selected = areaFilter != null,
                onClick = { sheet = FilterKind.AREA },
            )
            TvChip(
                text = "排序：${sortLabel(sort, sortByScore)}",
                selected = true,
                onClick = { sheet = FilterKind.SORT },
            )
        }

        // ── 网格 / C 模式两列列表 ───────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            when {
                loading && items.isEmpty() -> CategorySkeleton(lite = lite)
                error != null && items.isEmpty() -> TvErrorState(
                    error = error ?: AppError("E_UNKNOWN", "未知错误"),
                    onRetry = { reloadToken++ },
                )
                items.isEmpty() -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    TvEmptyState("这个分类暂时没有内容")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TvButton(
                            "换个子分类",
                            onClick = {
                                selectedTypeName = null
                                yearFilter = null
                                areaFilter = null
                            },
                        )
                    }
                }
                lite -> androidx.compose.runtime.CompositionLocalProvider(
                    LocalListScrolling provides gridState.isScrollInProgress,
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 12.dp, end = 40.dp),
                    ) {
                        items(
                            items = shown,
                            key = { "${it.sourceId}|${it.vodId}" },
                            contentType = { "lite-row" },
                        ) { vod ->
                            LiteListRow(
                                vod = vod,
                                onClick = { onOpenDetail(vod) },
                                thumbWidth = 72.dp,
                                thumbHeight = 96.dp,
                                rowHeight = 96.dp,
                            )
                        }
                    }
                }
                else -> {
                    val columns = tvGridColumns()
                    val contentWidth = tvWidthDp() - 150 - 96 - 18
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
                                contentType = { "poster" },
                            ) { vod ->
                                PosterCard(vod = vod, onClick = { onOpenDetail(vod) }, width = cardWidth)
                            }
                        }
                    }
                }
            }
        }

        // ── 页脚：三态互斥 ──────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TvLabel(
                text = buildString {
                    append("已加载 ${items.size} 条")
                    if (shown.size != items.size) append(" · 筛选后 ${shown.size} 条")
                },
                fontSize = dimens.caption,
                color = colors.textMuted,
                maxLines = 1,
            )
            Spacer(Modifier.width(12.dp))
            when {
                loading && items.isNotEmpty() -> TvLabel("加载中…", dimens.caption, colors.accent, maxLines = 1)
                loadMoreFailed -> TvButton("加载失败，点击重试", onClick = { loadMore() })
                hasMore -> TvLabel("继续下滑加载更多", dimens.caption, colors.textMuted, maxLines = 1)
                items.isNotEmpty() -> TvLabel("没有更多了", dimens.caption, colors.textMuted, maxLines = 1)
            }
        }
        DpadDownSink()
    }

    // ── 筛选浮层（不用下拉框）──────────────────────────────────────────────
    val currentSheet = sheet
    if (currentSheet != null) {
        val sheetTitle: String
        val options: List<String>
        val current: String
        when (currentSheet) {
            FilterKind.YEAR -> {
                sheetTitle = "年份"
                options = listOf("全部") + YEAR_OPTIONS
                current = yearFilter ?: "全部"
            }
            FilterKind.AREA -> {
                sheetTitle = "地区"
                options = listOf("全部") + AREA_OPTIONS
                current = areaFilter ?: "全部"
            }
            FilterKind.SORT -> {
                sheetTitle = "排序"
                options = listOf("最新", "最热", "评分")
                current = sortLabel(sort, sortByScore)
            }
        }
        OptionSheet(
            title = sheetTitle,
            options = options,
            selected = current,
            onPick = { picked ->
                val value = picked?.takeIf { it != "全部" }
                when (currentSheet) {
                    FilterKind.YEAR -> yearFilter = value
                    FilterKind.AREA -> areaFilter = value
                    FilterKind.SORT -> when (picked) {
                        "最热" -> {
                            sort = SourceRepository.Sort.HOT
                            sortByScore = false
                        }
                        "评分" -> {
                            sort = SourceRepository.Sort.LATEST
                            sortByScore = true
                        }
                        else -> {
                            sort = SourceRepository.Sort.LATEST
                            sortByScore = false
                        }
                    }
                }
            },
            onClose = { sheet = null },
        )
    }
}

private enum class FilterKind { YEAR, AREA, SORT }

private val YEAR_OPTIONS = listOf("2026", "2025", "2024", "2023", "2022")
private val AREA_OPTIONS = listOf("大陆", "港台", "日韩", "欧美", "其他")

private fun sortLabel(sort: SourceRepository.Sort, byScore: Boolean): String = when {
    byScore -> "评分"
    sort == SourceRepository.Sort.HOT -> "最热"
    else -> "最新"
}

/** 地区粗匹配：源返回的 vod_area 形如「中国大陆 / 日本 / 美国 / 中国香港」。 */
private fun areaMatches(area: String, filter: String): Boolean {
    if (area.isBlank()) return false
    val mainland = area.contains("大陆") || (area.contains("中国") && !area.contains("香港") && !area.contains("台湾"))
    val hkTw = area.contains("香港") || area.contains("台湾") || area.contains("澳门")
    val jpKr = area.contains("日本") || area.contains("韩国")
    val west = area.contains("美国") || area.contains("英国") || area.contains("法国") ||
        area.contains("德国") || area.contains("加拿大") || area.contains("欧美")
    return when (filter) {
        "大陆" -> mainland
        "港台" -> hkTw
        "日韩" -> jpKr
        "欧美" -> west
        else -> !mainland && !hkTw && !jpKr && !west
    }
}

/** 首屏骨架：A 模式 6 个海报卡，C 模式 6 行文字骨架。 */
@Composable
private fun CategorySkeleton(lite: Boolean) {
    val colors = LocalTvColors.current
    if (lite) {
        Column(Modifier.fillMaxSize()) {
            repeat(6) {
                Row(
                    Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(72.dp).height(96.dp).background(colors.surfaceHigh))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Box(Modifier.width(160.dp).height(16.dp).background(colors.surfaceHigh))
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.width(110.dp).height(12.dp).background(colors.surface))
                    }
                }
            }
        }
    } else {
        val columns = tvGridColumns()
        val contentWidth = tvWidthDp() - 150 - 96 - 18
        val cardWidth = ((contentWidth - (columns - 1) * 16) / columns).coerceIn(112, 200).dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(columns) {
                        SkeletonPosterCard(width = cardWidth)
                    }
                }
            }
        }
    }
}

/** C 模式文字胶囊：无边框，选中红字 + 下划线（UI_TUNE §3.3）。 */
@Composable
private fun LitePill(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    TvFocusable(
        modifier = Modifier.width(72.dp),
        onClick = onClick,
    ) { focused ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (focused) colors.surfaceHigh else Color.Transparent)
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TvLabel(
                text,
                dimens.body,
                if (selected || focused) colors.accent else colors.textMuted,
                weight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .width(28.dp)
                    .height(2.dp)
                    .background(if (selected) colors.accent else Color.Transparent),
            )
        }
    }
}
