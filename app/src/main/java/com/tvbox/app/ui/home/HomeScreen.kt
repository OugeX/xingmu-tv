package com.tvbox.app.ui.home

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvbox.app.core.AppDispatchers
import com.tvbox.app.ui.DpadDownSink
import com.tvbox.app.core.AppError
import com.tvbox.app.core.Result
import com.tvbox.app.data.CanonicalCategory
import com.tvbox.app.data.VodSummary
import com.tvbox.app.data.WatchProgress
import com.tvbox.app.data.WatchProgressStore
import com.tvbox.app.search.PinyinIndex
import com.tvbox.app.source.SourceRepository
import com.tvbox.app.ui.LocalTvColors
import com.tvbox.app.ui.LocalTvDimens
import com.tvbox.app.ui.LocalTvTier
import com.tvbox.app.ui.TvMode
import com.tvbox.app.ui.components.LiteListRow
import com.tvbox.app.ui.components.LocalListScrolling
import com.tvbox.app.ui.components.PosterImage
import com.tvbox.app.ui.components.PosterRow
import com.tvbox.app.ui.components.TvButton
import com.tvbox.app.ui.components.TvEmptyState
import com.tvbox.app.ui.components.TvErrorState
import com.tvbox.app.ui.components.TvLabel
import com.tvbox.app.ui.components.TvFocusable
import com.tvbox.app.ui.components.TvLoading
import com.tvbox.app.ui.components.TvSkeletonRows
import com.tvbox.app.ui.tvHeroHeight
import com.tvbox.app.ui.tvPosterWidth
import com.tvbox.app.ui.tvWidthDp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private data class HomeRow(val title: String, val typeId: Int, val items: List<VodSummary>)

private sealed interface HomeUi {
    data object Loading : HomeUi
    data class Ready(val rows: List<HomeRow>, val hero: VodSummary?, val progress: List<WatchProgress>) : HomeUi
    data class Failed(val error: AppError) : HomeUi
}

@Composable
fun HomeScreen(
    repo: SourceRepository,
    pinyin: PinyinIndex,
    progressStore: WatchProgressStore,
    mode: TvMode,
    onOpenDetail: (VodSummary) -> Unit,
    onOpenCategory: (Int, String) -> Unit,
) {
    var reload by remember { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf<HomeUi>(HomeUi.Loading) }

    LaunchedEffect(reload) {
        ui = HomeUi.Loading
        val definitions = listOf(
            CanonicalCategory.MOVIE to "热门电影",
            CanonicalCategory.TV to "最新剧集",
            CanonicalCategory.ANIME to "动漫热播",
            CanonicalCategory.VARIETY to "综艺更新",
        )
        val results = coroutineScope {
            definitions.map { (typeId, _) ->
                async { typeId to repo.list(typeId, 1) }
            }.awaitAll()
        }
        val rows = results.map { (typeId, result) ->
            val title = definitions.first { it.first == typeId }.second
            HomeRow(title, typeId, result.getOrNull().orEmpty())
        }
        val all = rows.flatMap { it.items }
        pinyin.index(all)
        val progress = withContext(AppDispatchers.Io) { progressStore.list() }.take(12)
        val errors = results.mapNotNull { it.second.errorOrNull() }
        ui = if (rows.all { it.items.isEmpty() } && errors.isNotEmpty()) {
            HomeUi.Failed(errors.first())
        } else {
            HomeUi.Ready(rows, all.firstOrNull(), progress)
        }
    }

    when (val state = ui) {
        is HomeUi.Loading -> if (mode == TvMode.CINEMA) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                TvSkeletonRows(rows = 3, columns = 6)
            }
        } else {
            TvLoading()
        }

        is HomeUi.Failed -> TvErrorState(error = state.error, onRetry = { reload++ })

        is HomeUi.Ready -> if (mode == TvMode.LITE) {
            LiteHome(state, onOpenDetail)
        } else {
            CinemaHome(state, onOpenDetail, onOpenCategory)
        }
    }
}

@Composable
private fun CinemaHome(
    state: HomeUi.Ready,
    onOpenDetail: (VodSummary) -> Unit,
    onOpenCategory: (Int, String) -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val posterWidth = tvPosterWidth()
    val largeText = LocalTvTier.current.isLarge

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // 大字号档默认收起 Hero（UI_SPEC §0.6）：直接进列表，一屏信息更少更好读
        if (!largeText) {
            state.hero?.let { hero ->
                item(key = "hero") {
                    HeroSection(hero = hero, onOpenDetail = onOpenDetail)
                }
            }
        }

        if (state.progress.isNotEmpty()) {
            item(key = "continue") {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvLabel("继续观看", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        items(
                            items = state.progress,
                            key = { "${it.sourceId}|${it.vodId}" },
                            contentType = { "continue" },
                        ) { progress ->
                            ContinueCard(progress, onOpenDetail)
                        }
                    }
                }
            }
        }

        items(state.rows, key = { it.typeId }, contentType = { "row" }) { row ->
            PosterRow(
                title = row.title,
                items = row.items,
                onItemClick = onOpenDetail,
                onMore = if (row.items.isNotEmpty()) {
                    { onOpenCategory(row.typeId, row.title) }
                } else {
                    null
                },
                posterWidth = posterWidth,
            )
        }

        item(key = "focus-sink") { DpadDownSink() }

        item(key = "footer") {
            TvLabel(
                text = "内置 7 个点播源 · 数据来源 MacCMS · 低端盒子已降级渲染",
                fontSize = dimens.caption,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun HeroSection(hero: VodSummary, onOpenDetail: (VodSummary) -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val heroHeight = tvHeroHeight()
    val width = tvWidthDp()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .clip(RoundedCornerShape(10.dp)),
    ) {
        PosterImage(
            url = hero.pic,
            thumbUrl = hero.picThumb,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            requestWidth = width,
            requestHeight = (heroHeight.value * 1.6f).toInt().coerceAtLeast(540),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        0.78f to colors.bg.copy(alpha = 0.88f),
                        1f to colors.bg,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            TvLabel(
                "今日推荐 · ${hero.typeName.ifBlank { "热播" }}",
                dimens.caption,
                colors.accent,
                weight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            TvLabel(
                hero.name,
                dimens.heroTitle,
                colors.text,
                weight = FontWeight.Black,
                maxLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            TvLabel(
                listOfNotNull(
                    hero.year.takeIf { it.isNotBlank() },
                    hero.typeName.takeIf { it.isNotBlank() },
                    hero.remarks.takeIf { it.isNotBlank() },
                    hero.score.takeIf { it.isNotBlank() && it != "0.0" }?.let { "评分 $it" },
                ).joinToString(" · "),
                dimens.body,
                colors.textSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("▶ 立即播放", onClick = { onOpenDetail(hero) }, primary = true)
                TvButton("选集", onClick = { onOpenDetail(hero) })
                TvButton("切换源", onClick = { onOpenDetail(hero) })
            }
        }
    }
}

@Composable
private fun ContinueCard(progress: WatchProgress, onOpenDetail: (VodSummary) -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val summary = remember(progress) {
        VodSummary(
            vodId = progress.vodId,
            sourceId = progress.sourceId,
            name = progress.name,
            pic = progress.pic,
            remarks = progress.episodeTitle,
            year = "",
            typeId = "",
            typeName = "",
            score = "",
        )
    }
    TvFocusable(
        modifier = Modifier.width(220.dp),
        shape = RoundedCornerShape(6.dp),
        onClick = { onOpenDetail(summary) },
    ) { focused ->
        Column(Modifier.background(if (focused) colors.surfaceHigh else Color.Transparent)) {
            PosterImage(
                url = progress.pic,
                modifier = Modifier.fillMaxWidth().height(124.dp),
                requestWidth = 320,
                requestHeight = 180,
            )
            Spacer(Modifier.height(6.dp))
            TvLabel(progress.name, dimens.cardTitle, colors.text, maxLines = 1)
            TvLabel(
                "${progress.episodeTitle} · 已看 ${formatMinutes(progress.positionMs)}",
                dimens.caption,
                colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

private fun formatMinutes(positionMs: Long): String {
    val totalMinutes = (positionMs / 60_000L).toInt()
    return if (totalMinutes <= 0) "0 分钟" else "$totalMinutes 分钟"
}

@Composable
private fun LiteHome(state: HomeUi.Ready, onOpenDetail: (VodSummary) -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    // t28：派生列表 remember 住，避免每次重组都 flatMap 出新 List（低端机上就是滚动抖动源）
    val flat = remember(state.rows) { state.rows.flatMap { row -> row.items.map { row.title to it } } }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvLabel("最新更新", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
            Spacer(Modifier.width(1.dp).weight(1f))
            TvLabel("C 保命模式 · ${flat.size} 条", dimens.caption, colors.textMuted)
        }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalListScrolling provides gridState.isScrollInProgress,
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(
                    items = flat,
                    key = { "${it.second.sourceId}|${it.second.vodId}" },
                    contentType = { "lite-row" },
                ) { (badge, vod) ->
                    LiteListRow(vod = vod, onClick = { onOpenDetail(vod) }, badge = badge)
                }
            }
        }
        if (flat.isEmpty()) {
            TvEmptyState("暂无内容，检查网络后重试")
        }
        DpadDownSink()
    }
}
