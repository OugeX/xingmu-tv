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
 * 分类页（t29 三层结构 + t31 服务端筛选修正）。
 *
 *   一级 Tab（电影/剧集/动漫/综艺/海外/短剧）→ 服务端 `t=<canonical type_id>`
 *   └ 二级子分类 chips（全部 / 喜剧片 / 动作片 …）→ 服务端 `t=<子分类 type_id>`（喜剧片=22，total 7442）
 *     └ 年份筛选 → 服务端 `year=YYYY`（t=22&year=2026 → total 165）
 *       └ 面包屑「电影 › 喜剧片 · 2026」＋「共 N 部 / 已加载 M 条」
 *
 * t31 关键修正（captain 实测）：
 *  - 子分类**不再**对已加载页做客户端过滤（这正是「电影→喜剧只有 4 部」的根因）；
 *  - `area=` 服务端忽略 → 地区筛选下线（实现保留在 [areaMatches]，等有源支持再接）；
 *  - `by=` 服务端忽略 → 排序只在本页生效，按钮文案写明「排序·本页」；
 *    本页热度按 [VodRanking]：sourceCount desc → 更新时间 desc → 评分（<1.0 假分归零）。
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
    // t31：二级子分类带服务端 type_id（从已加载项的 type_id/type_name 建映射）
    var selectedSubName by remember(primaryTab) { mutableStateOf<String?>(null) }
    var selectedSubTypeId by remember(primaryTab) { mutableStateOf<Int?>(null) }
    var yearFilter by remember(primaryTab) { mutableStateOf<String?>(null) }
    var sortMode by remember(primaryTab) { mutableStateOf(SortMode.SERVER_LATEST) }
    var sheet by remember { mutableStateOf<FilterKind?>(null) }

    val baseTypeId = if (primaryTab == CanonicalCategory.OVERSEAS_ROOT) overseasSection else primaryTab
    // 服务端查询用的真实分类 id：选了子分类就用子分类 id
    val queryTypeId = selectedSubTypeId ?: baseTypeId

    var items by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var serverTotal by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var loadMoreFailed by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(queryTypeId, yearFilter, reloadToken) {
        loading = true
        error = null
        loadMoreFailed = false
        hasMore = true
        page = 1
        when (val result = repo.listPage(queryTypeId, 1, sortMode.serverSort, yearFilter)) {
            is Result.Ok -> {
                items = result.value.items
                serverTotal = result.value.total
                hasMore = result.value.items.size >= PAGE_SIZE
                pinyin.index(result.value.items)
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

    // name → 服务端 type_id（同一子分类在不同页重复出现，取第一个）
    val subCategories = remember(items) {
        val seen = LinkedHashMap<String, Int>()
        items.forEach { vod ->
            val name = vod.typeName.takeIf { it.isNotBlank() } ?: return@forEach
            if (name == CanonicalCategory.labelOf(baseTypeId)) return@forEach
            val id = vod.typeId.toIntOrNull() ?: return@forEach
            if (!seen.containsKey(name)) seen[name] = id
        }
        seen.entries.take(12).map { it.key to it.value }
    }

    // t31：排序只作用于本页（服务端 by= 无效）
    val shown = remember(items, sortMode) {
        when (sortMode) {
            SortMode.SERVER_LATEST -> items
            SortMode.HOT_LOCAL -> VodRanking.rankHot(items)
            SortMode.SCORE_LOCAL -> VodRanking.rankByScore(items)
        }
    }

    val rootLabel = CanonicalCategory.labelOf(primaryTab) ?: title
    val breadcrumb = remember(rootLabel, baseTypeId, selectedSubName, yearFilter, sortMode) {
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
            val filters = listOfNotNull(yearFilter, sortMode.label.takeIf { sortMode != SortMode.SERVER_LATEST })
            if (filters.isNotEmpty()) append(" · ").append(filters.joinToString(" · "))
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ── 顶部：返回 + 面包屑 + 服务端总数 ────────────────────────────────
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvButton("‹ 返回", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            TvLabel(breadcrumb, dimens.body, colors.text, weight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(1.dp).weight(1f))
            TvLabel(
                if (serverTotal > 0) "共 $serverTotal 部" else "已加载 ${items.size} 条",
                dimens.caption,
                colors.accent,
                maxLines = 1,
            )
        }

        // ── 一级 Tab ────────────────────────────────────────────────────────
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            items(CanonicalCategory.primaryTabs, key = { it.typeId }, contentType = { "primary-tab" }) { tab ->
                if (lite) {
                    LitePill(
                        text = tab.label,
                        selected = primaryTab == tab.typeId,
                        onClick = {
                            primaryTab = tab.typeId
                            selectedSubName = null
                            selectedSubTypeId = null
                        },
                    )
                } else {
                    TvChip(
                        text = tab.label,
                        selected = primaryTab == tab.typeId,
                        onClick = {
                            primaryTab = tab.typeId
                            selectedSubName = null
                            selectedSubTypeId = null
                        },
                    )
                }
            }
        }

        // 海外二级分区
        if (primaryTab == CanonicalCategory.OVERSEAS_ROOT) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CanonicalCategory.overseas, key = { it.id }, contentType = { "overseas" }) { section ->
                    TvChip(
                        text = section.label,
                        selected = overseasSection == section.id,
                        onClick = {
                            overseasSection = section.id
                            selectedSubName = null
                            selectedSubTypeId = null
                        },
                    )
                }
            }
        }

        // ── 二级子分类（服务端 t=<子分类 id>）───────────────────────────────
        if (subCategories.isNotEmpty() || selectedSubName != null) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") {
                    TvChip(
                        "全部",
                        selected = selectedSubName == null,
                        onClick = {
                            selectedSubName = null
                            selectedSubTypeId = null
                        },
                    )
                }
                items(subCategories, key = { it.first }, contentType = { "sub-category" }) { (name, subTypeId) ->
                    TvChip(
                        name,
                        selected = selectedSubName == name,
                        onClick = {
                            selectedSubName = name
                            selectedSubTypeId = subTypeId
                        },
                    )
                }
            }
        }

        // ── 筛选：年份走服务端；排序只标「本页」；地区已下线 ────────────────
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TvChip(
                text = "年份：${yearFilter ?: "全部"}",
                selected = yearFilter != null,
                onClick = { sheet = FilterKind.YEAR },
            )
            // t31：地区筛选下线（bfzy1 忽略 area=，本地过滤会造成「只有几部」的错觉）
            TvChip(
                text = "排序·本页：${sortMode.label}",
                selected = sortMode != SortMode.SERVER_LATEST,
                onClick = { sheet = FilterKind.SORT },
            )
            if (sortMode != SortMode.SERVER_LATEST) {
                TvLabel("仅本页生效（服务端不排序）", dimens.caption, colors.textMuted, maxLines = 1)
            }
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
                                selectedSubName = null
                                selectedSubTypeId = null
                                yearFilter = null
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

        // ── 页脚：共 N 部 + 三态互斥 ────────────────────────────────────────
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
        DpadDownSink()
    }

    // ── 筛选浮层（胶囊 + OK，不用下拉框）──────────────────────────────────
    val currentSheet = sheet
    if (currentSheet != null) {
        val sheetTitle: String
        val options: List<String>
        val current: String
        when (currentSheet) {
            FilterKind.YEAR -> {
                sheetTitle = "年份（服务端）"
                options = listOf("全部") + YEAR_OPTIONS
                current = yearFilter ?: "全部"
            }
            FilterKind.SORT -> {
                sheetTitle = "排序·本页（服务端 by= 无效）"
                options = SortMode.entries.map { it.label }
                current = sortMode.label
            }
        }
        OptionSheet(
            title = sheetTitle,
            options = options,
            selected = current,
            onPick = { picked ->
                when (currentSheet) {
                    FilterKind.YEAR -> yearFilter = picked?.takeIf { it != "全部" }
                    FilterKind.SORT -> sortMode = SortMode.entries.firstOrNull { it.label == picked } ?: SortMode.SERVER_LATEST
                }
            },
            onClose = { sheet = null },
        )
    }
}

private const val PAGE_SIZE = 20

private enum class FilterKind { YEAR, SORT }

/** 排序模式：服务端只认最新（time），其余只作用于本页。 */
private enum class SortMode(
    val label: String,
    val serverSort: SourceRepository.Sort,
) {
    SERVER_LATEST("最新（服务端）", SourceRepository.Sort.LATEST),
    HOT_LOCAL("热度", SourceRepository.Sort.LATEST),
    SCORE_LOCAL("评分", SourceRepository.Sort.LATEST),
}

private val YEAR_OPTIONS = listOf("2026", "2025", "2024", "2023", "2022")

/**
 * 地区粗匹配（t31 下线，保留实现）：
 * bfzy1 忽略 `area=`，本地过滤已加载页会让人误以为「只有几部」；等有支持 area 的源再启用。
 */
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
