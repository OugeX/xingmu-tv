package com.xingmu.tv.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingmu.tv.core.AppError
import com.xingmu.tv.core.Result
import com.xingmu.tv.data.CanonicalCategory
import com.xingmu.tv.data.CapabilityCache
import com.xingmu.tv.data.CategoryBoards
import com.xingmu.tv.data.CategoryFilterStore
import com.xingmu.tv.data.FacetCountCache
import com.xingmu.tv.data.FilterCapability
import com.xingmu.tv.data.VodRanking
import com.xingmu.tv.data.VodSummary
import com.xingmu.tv.search.PinyinIndex
import com.xingmu.tv.source.SourceRepository
import com.xingmu.tv.ui.ContentGate
import com.xingmu.tv.ui.DpadDownSink
import com.xingmu.tv.ui.LocalTvColors
import com.xingmu.tv.ui.LocalTvDimens
import com.xingmu.tv.ui.TvMode
import com.xingmu.tv.ui.components.LiteListRow
import com.xingmu.tv.ui.components.OptionSheet
import com.xingmu.tv.ui.components.PosterCard
import com.xingmu.tv.ui.components.PosterRow
import com.xingmu.tv.ui.components.SkeletonPosterCard
import com.xingmu.tv.ui.components.TvButton
import com.xingmu.tv.ui.components.TvChip
import com.xingmu.tv.ui.components.TvEmptyState
import com.xingmu.tv.ui.components.TvErrorState
import com.xingmu.tv.ui.components.TvFocusable
import com.xingmu.tv.ui.components.TvLabel
import com.xingmu.tv.ui.tvGridColumns
import com.xingmu.tv.ui.tvWidthDp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 分类页（t29 三层 → t31 服务端筛选 → t34 体验增强）。
 *
 * 布局：顶部一级 Tab → **左侧竖排二级**（带数量角标）→ 右侧（筛选行 / 三条榜单 / 全量网格）。
 *
 * t34 七项：
 *  1) 竖排二级：一屏 ≥8 个（44dp/项），UP/DOWN 可达，LEFT 可回 Tab 行（空间焦点搜索）；
 *  2) 数量角标：每类取一次 `t=<id>&pg=1` 的 total，24h 缓存；
 *  3) 能力探测：带 area/by 探针比较 total 决定是否显示筛选（7 天缓存，bfzy1 两者都隐藏）；
 *  4) 一级首屏三条榜单（本周更新 / 评分最高 / 最近完结），往下才是全量网格；
 *  5) 状态记忆（一级+二级+年份）+ 顶部「重置」；切一级保留年份、清空二级；
 *  6) 空结果自动放宽：total=0 去年份重试一次，仍空给「该组合暂无内容 / 查看全部」；
 *  7) 本类搜索：`wd=` + `t=` 组合，只在当前分类内搜。
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
    val context = LocalContext.current
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()
    val lite = mode == TvMode.LITE

    val memory = remember { CategoryFilterStore(context) }
    val countCache = remember { FacetCountCache(context) }
    val capabilityCache = remember { CapabilityCache(context) }

    // ── 状态记忆：一级/二级/年份 ─────────────────────────────────────────
    val saved = remember(typeId) { memory.read() }
    var primaryTab by remember(typeId) { mutableIntStateOf(typeId) }
    var overseasSection by remember { mutableIntStateOf(CanonicalCategory.overseas.first().id) }
    var selectedSubName by remember(primaryTab) {
        mutableStateOf(saved?.subName?.takeIf { saved.primaryTypeId == typeId })
    }
    var selectedSubTypeId by remember(primaryTab) {
        mutableStateOf(saved?.subTypeId?.takeIf { saved.primaryTypeId == typeId })
    }
    // 年份跨一级 Tab 保留（切 Tab 只清二级）
    var yearFilter by remember { mutableStateOf(saved?.year) }
    var sortMode by remember(primaryTab) { mutableStateOf(SortMode.SERVER_LATEST) }
    var sheet by remember { mutableStateOf<FilterKind?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var searchKeyword by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }

    val baseTypeId = if (primaryTab == CanonicalCategory.OVERSEAS_ROOT) overseasSection else primaryTab
    val queryTypeId = selectedSubTypeId ?: baseTypeId

    var items by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var serverTotal by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var loadMoreFailed by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    // 空结果放宽：每次筛选变化只放宽一次
    var relaxedYear by remember { mutableStateOf(false) }
    var emptyAfterRelax by remember { mutableStateOf(false) }

    var capability by remember { mutableStateOf(FilterCapability()) }
    var counts by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }

    // 能力探测（缓存 7 天；bfzy1 → 地区/排序都不支持）
    LaunchedEffect(baseTypeId) {
        val key = "cap|$baseTypeId"
        val cached = capabilityCache.read(key)
        if (cached != null) {
            capability = cached
            return@LaunchedEffect
        }
        when (val r = repo.probeFilterCapability(baseTypeId)) {
            is Result.Ok -> {
                capability = r.value
                capabilityCache.put(key, r.value)
            }
            is Result.Err -> capability = FilterCapability()
        }
    }

    // 一级数据加载（服务端 t= / year=）
    LaunchedEffect(queryTypeId, yearFilter, reloadToken) {
        loading = true
        error = null
        loadMoreFailed = false
        emptyAfterRelax = false
        hasMore = true
        page = 1
        when (val result = repo.listPage(queryTypeId, 1, sortMode.serverSort, yearFilter)) {
            is Result.Ok -> {
                items = result.value.items
                serverTotal = result.value.total
                hasMore = result.value.items.size >= PAGE_SIZE
                pinyin.index(result.value.items)
                // 空结果自动放宽：total=0 → 去年份重试一次
                if (serverTotal == 0 && yearFilter != null && !relaxedYear) {
                    relaxedYear = true
                    yearFilter = null
                    loading = false
                    return@LaunchedEffect
                }
                emptyAfterRelax = serverTotal == 0
            }
            is Result.Err -> {
                items = emptyList()
                serverTotal = 0
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
            when (val result = repo.listPage(queryTypeId, page + 1, sortMode.serverSort, yearFilter)) {
                is Result.Ok -> {
                    val known = items.mapTo(HashSet()) { "${it.sourceId}|${it.vodId}" }
                    val fresh = result.value.items.filter { "${it.sourceId}|${it.vodId}" !in known }
                    items = items + fresh
                    if (result.value.total > 0) serverTotal = result.value.total
                    page += 1
                    hasMore = result.value.items.size >= PAGE_SIZE
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

    // 子分类：name → 服务端 type_id（来自已加载项）
    val subCategories = remember(items) {
        val seen = LinkedHashMap<String, Int>()
        items.forEach { vod ->
            val name = vod.typeName.takeIf { it.isNotBlank() } ?: return@forEach
            if (name == CanonicalCategory.labelOf(baseTypeId)) return@forEach
            val id = vod.typeId.toIntOrNull() ?: return@forEach
            if (!seen.containsKey(name)) seen[name] = id
        }
        seen.entries.take(24).map { it.key to it.value }
    }

    // 数量角标：先读 24h 缓存，缺的按顺序补（最多 12 个，避免一次打太多请求）
    LaunchedEffect(subCategories) {
        val known = subCategories.mapNotNull { (_, id) -> countCache.total(id)?.let { id to it } }.toMap()
        if (known.isNotEmpty()) counts = counts + known
        subCategories.take(12).forEach { (_, id) ->
            if (counts[id] != null || countCache.total(id) != null) return@forEach
            when (val r = repo.countOf(id)) {
                is Result.Ok -> {
                    if (r.value > 0) {
                        countCache.put(id, r.value)
                        counts = counts + (id to r.value)
                    }
                }
                is Result.Err -> Unit
            }
            delay(60)
        }
    }

    // 榜单（一级首屏、未选子分类/年份、非搜索态时展示）
    val showBoards = selectedSubTypeId == null && yearFilter == null && !searching && items.isNotEmpty()
    val boardUpdated = remember(items) { CategoryBoards.mostUpdated(items, 8) }
    val boardRated = remember(items) { CategoryBoards.topRated(items, 8) }
    val boardCompleted = remember(items) { CategoryBoards.recentlyCompleted(items, 8) }

    val shown = remember(items, sortMode, searching) {
        when {
            searching -> items
            sortMode == SortMode.HOT_LOCAL -> VodRanking.rankHot(items)
            sortMode == SortMode.SCORE_LOCAL -> VodRanking.rankByScore(items)
            else -> items
        }
    }

    val rootLabel = CanonicalCategory.labelOf(primaryTab) ?: title
    val breadcrumb = remember(rootLabel, baseTypeId, selectedSubName, yearFilter, searching, searchKeyword) {
        buildString {
            append(rootLabel)
            if (primaryTab == CanonicalCategory.OVERSEAS_ROOT) {
                CanonicalCategory.overseas.firstOrNull { it.id == baseTypeId }?.let {
                    append(" › ")
                    append(it.label)
                }
            }
            if (selectedSubName != null) {
                append(" › ")
                append(selectedSubName)
            }
            if (yearFilter != null) {
                append(" · ")
                append(yearFilter)
            }
            if (searching) {
                append(" · 搜索「")
                append(searchKeyword)
                append("」")
            }
        }
    }

    // 状态落盘（一级+二级+年份）
    LaunchedEffect(primaryTab, selectedSubTypeId, selectedSubName, yearFilter) {
        if (!searching) memory.write(primaryTab, selectedSubTypeId, selectedSubName, yearFilter)
    }

    fun resetFilters() {
        selectedSubName = null
        selectedSubTypeId = null
        yearFilter = null
        relaxedYear = false
        searching = false
        searchKeyword = ""
        sortMode = SortMode.SERVER_LATEST
        memory.reset()
    }

    Column(Modifier.fillMaxSize()) {
        // ── 顶部：返回 + 面包屑 + 重置 + 总数 ───────────────────────────────
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvButton("‹ 返回", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            TvLabel(breadcrumb, dimens.body, colors.text, weight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(1.dp).weight(1f))
            TvButton("重置", onClick = { resetFilters() })
            Spacer(Modifier.width(10.dp))
            TvLabel(
                if (serverTotal > 0) "共 $serverTotal 部" else "已加载 ${items.size} 条",
                dimens.caption,
                colors.accent,
                maxLines = 1,
            )
        }

        // ── 一级 Tab（顶部横排）────────────────────────────────────────────
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            items(CanonicalCategory.primaryTabs, key = { it.typeId }, contentType = { "primary-tab" }) { tab ->
                TvChip(
                    text = tab.label,
                    selected = primaryTab == tab.typeId,
                    onClick = {
                        primaryTab = tab.typeId
                        // 切一级：保留年份、清空二级
                        selectedSubName = null
                        selectedSubTypeId = null
                        searching = false
                        relaxedYear = false
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f)) {
            // ── 左侧竖排二级 ─────────────────────────────────────────────
            SubCategoryRail(
                width = if (lite) 180.dp else 210.dp,
                subCategories = subCategories,
                counts = counts,
                selectedName = selectedSubName,
                onSelect = { name, id ->
                    selectedSubName = name
                    selectedSubTypeId = id
                    searching = false
                    relaxedYear = false
                },
            )

            Spacer(Modifier.width(12.dp))

            // ── 右侧：筛选行 + 本类搜索 + 榜单 + 网格 ────────────────────
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TvChip(
                        text = "年份：${yearFilter ?: "全部"}",
                        selected = yearFilter != null,
                        onClick = { sheet = FilterKind.YEAR },
                    )
                    // 能力探测：服务端不支持就不显示（bfzy1 两者都不支持）
                    if (capability.showArea) {
                        TvChip("地区：全部", selected = false, onClick = { sheet = FilterKind.AREA })
                    }
                    if (capability.showSort) {
                        TvChip(
                            text = "排序：${sortMode.label}",
                            selected = sortMode != SortMode.SERVER_LATEST,
                            onClick = { sheet = FilterKind.SORT },
                        )
                    } else {
                        TvLabel("该源不支持地区/排序筛选", dimens.caption, colors.textMuted, maxLines = 1)
                    }
                    if (capability.partialNote) {
                        TvLabel("部分源不支持", dimens.caption, colors.textMuted, maxLines = 1)
                    }
                    Spacer(Modifier.width(1.dp).weight(1f))
                    TvButton(
                        if (searching) "退出本类搜索" else "本类搜索",
                        onClick = {
                            if (searching) {
                                searching = false
                                searchKeyword = ""
                                reloadToken++
                            } else {
                                showSearch = true
                            }
                        },
                    )
                }

                if (searching) {
                    TvLabel("搜索「$searchKeyword」· 共 ${items.size} 条", dimens.caption, colors.accent, maxLines = 1)
                }

                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f)) {
                    when {
                        loading && items.isEmpty() -> CategorySkeleton(lite = lite)
                        error != null && items.isEmpty() -> TvErrorState(
                            error = error ?: AppError("E_UNKNOWN", "未知错误"),
                            onRetry = { reloadToken++ },
                        )
                        items.isEmpty() && emptyAfterRelax -> Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            TvEmptyState("该组合暂无内容")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                TvButton("查看全部${rootLabel}", onClick = { resetFilters() })
                            }
                        }
                        items.isEmpty() -> TvEmptyState("这个分类暂时没有内容")
                        else -> LazyVerticalGrid(
                            columns = GridCells.Fixed(if (lite) 2 else tvGridColumns()),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(if (lite) 4.dp else 16.dp),
                            verticalArrangement = Arrangement.spacedBy(if (lite) 0.dp else 16.dp),
                            contentPadding = PaddingValues(bottom = 20.dp, end = if (lite) 40.dp else 0.dp),
                        ) {
                            if (showBoards) {
                                if (boardUpdated.isNotEmpty()) {
                                    item(key = "board-updated", span = { GridItemSpan(maxLineSpan) }) {
                                        BoardRow("本周更新最多", boardUpdated, onOpenDetail)
                                    }
                                }
                                if (boardRated.isNotEmpty()) {
                                    item(key = "board-rated", span = { GridItemSpan(maxLineSpan) }) {
                                        BoardRow("评分最高", boardRated, onOpenDetail)
                                    }
                                }
                                if (boardCompleted.isNotEmpty()) {
                                    item(key = "board-completed", span = { GridItemSpan(maxLineSpan) }) {
                                        BoardRow("最近完结", boardCompleted, onOpenDetail)
                                    }
                                }
                            }
                            items(
                                items = shown,
                                key = { "${it.sourceId}|${it.vodId}" },
                                contentType = { if (lite) "lite-row" else "poster" },
                            ) { vod ->
                                if (lite) {
                                    LiteListRow(
                                        vod = vod,
                                        onClick = { onOpenDetail(vod) },
                                        thumbWidth = 72.dp,
                                        thumbHeight = 96.dp,
                                        rowHeight = 96.dp,
                                    )
                                } else {
                                    PosterCard(
                                        vod = vod,
                                        onClick = { onOpenDetail(vod) },
                                        width = posterWidth(),
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 页脚：共 N 部 + 三态互斥 ─────────────────────────────
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvLabel(
                        text = if (serverTotal > 0) "共 $serverTotal 部 · 已加载 ${items.size} 条" else "已加载 ${items.size} 条",
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
            }
        }
        DpadDownSink()
    }

    // ── 筛选浮层 ─────────────────────────────────────────────────────────
    val currentSheet = sheet
    if (currentSheet != null) {
        val sheetTitle = when (currentSheet) {
            FilterKind.YEAR -> "年份（服务端）"
            FilterKind.AREA -> "地区（服务端不支持）"
            FilterKind.SORT -> "排序·本页"
        }
        val options = when (currentSheet) {
            FilterKind.YEAR -> listOf("全部") + YEAR_OPTIONS
            FilterKind.AREA -> listOf("全部", "大陆", "港台", "日韩", "欧美")
            FilterKind.SORT -> SortMode.entries.map { it.label }
        }
        val current = when (currentSheet) {
            FilterKind.YEAR -> yearFilter ?: "全部"
            FilterKind.AREA -> "全部"
            FilterKind.SORT -> sortMode.label
        }
        OptionSheet(
            title = sheetTitle,
            options = options,
            selected = current,
            onPick = { picked ->
                when (currentSheet) {
                    FilterKind.YEAR -> {
                        yearFilter = picked?.takeIf { it != "全部" }
                        relaxedYear = false
                    }
                    FilterKind.AREA -> Unit
                    FilterKind.SORT -> sortMode = SortMode.entries.firstOrNull { it.label == picked }
                        ?: SortMode.SERVER_LATEST
                }
            },
            onClose = { sheet = null },
        )
    }

    // ── 本类搜索浮层（wd= + t=）──────────────────────────────────────────
    if (showSearch) {
        InCategorySearchSheet(
            initial = searchKeyword,
            onCancel = { showSearch = false },
            onSubmit = { keyword ->
                showSearch = false
                val query = keyword.trim()
                if (query.isNotEmpty()) {
                    searchKeyword = query
                    searching = true
                    scope.launch {
                        when (val r = repo.searchInCategory(queryTypeId, query, 1)) {
                            is Result.Ok -> {
                                items = r.value
                                serverTotal = r.value.size
                                hasMore = false
                                pinyin.index(r.value)
                            }
                            is Result.Err -> error = r.error
                        }
                    }
                }
            },
        )
    }
}

/** 左侧竖排二级分类：一屏 ≥8 个（44dp/项），UP/DOWN 可达，带数量角标。 */
@Composable
private fun SubCategoryRail(
    width: Dp,
    subCategories: List<Pair<String, Int>>,
    counts: Map<Int, Int>,
    selectedName: String?,
    onSelect: (String?, Int?) -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Column(Modifier.width(width).fillMaxHeight()) {
        TvLabel("分类", dimens.caption, colors.textMuted, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "all") {
                RailItem(label = "全部", count = null, selected = selectedName == null, onClick = { onSelect(null, null) })
            }
            items(subCategories, key = { it.second }, contentType = { "rail" }) { (name, id) ->
                RailItem(label = name, count = counts[id], selected = selectedName == name, onClick = { onSelect(name, id) })
            }
        }
    }
}

@Composable
private fun RailItem(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    TvFocusable(modifier = Modifier.fillMaxWidth(), onClick = onClick) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(if (selected || focused) colors.surfaceHigh else Color.Transparent)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .background(if (selected) colors.accent else Color.Transparent),
            )
            Spacer(Modifier.width(8.dp))
            TvLabel(
                label,
                dimens.body,
                if (selected) colors.accent else colors.textSecondary,
                weight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (count != null && count > 0) {
                TvLabel(countText(count), dimens.caption, colors.textMuted, maxLines = 1)
            }
        }
    }
}

/** 数量角标：≥10000 显示「1.2k」形式，避免窄栏被撑爆。 */
internal fun countText(count: Int): String = when {
    count >= 10_000 -> "${count / 1000}.${(count % 1000) / 100}k"
    else -> count.toString()
}

/** 榜单一行（复用 PosterRow 的视觉，固定 8 部）。 */
@Composable
private fun BoardRow(title: String, items: List<VodSummary>, onOpenDetail: (VodSummary) -> Unit) {
    PosterRow(
        title = title,
        items = items,
        onItemClick = onOpenDetail,
        posterWidth = 116.dp,
    )
}

/** 本类搜索输入浮层（遥控器用系统输入法 / adb 输入）。 */
@Composable
private fun InCategorySearchSheet(
    initial: String,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focus.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(560.dp)
                .background(colors.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvLabel("本类搜索（只在当前分类内）", dimens.cardTitle, colors.text, weight = FontWeight.SemiBold)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(colors.surfaceHigh)
                    .focusRequester(focus)
                    .focusable()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    TvLabel("输入片名关键词", dimens.body, colors.textMuted)
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = colors.text, fontSize = 18.sp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("搜索", onClick = { onSubmit(text) }, primary = true)
                TvButton("取消", onClick = onCancel)
            }
        }
    }
}

private const val PAGE_SIZE = 20

private enum class FilterKind { YEAR, AREA, SORT }

private enum class SortMode(val label: String, val serverSort: SourceRepository.Sort) {
    SERVER_LATEST("最新（服务端）", SourceRepository.Sort.LATEST),
    HOT_LOCAL("热度（本页）", SourceRepository.Sort.LATEST),
    SCORE_LOCAL("评分（本页）", SourceRepository.Sort.LATEST),
}

private val YEAR_OPTIONS = listOf("2026", "2025", "2024", "2023", "2022")

/** 卡片宽度（左侧竖排占 210dp，比 t31 略窄）。 */
@Composable
private fun posterWidth(): Dp {
    val columns = tvGridColumns()
    val contentWidth = tvWidthDp() - 210 - 96 - 18 - 12
    return (((contentWidth - (columns - 1) * 16) / columns).coerceIn(112, 200)).dp
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
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(columns) {
                        SkeletonPosterCard(width = posterWidth())
                    }
                }
            }
        }
    }
}
