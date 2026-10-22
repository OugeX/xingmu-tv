package com.xingmu.tv.ui.home

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xingmu.tv.core.AppDispatchers
import com.xingmu.tv.ui.DpadDownSink
import com.xingmu.tv.core.AppError
import com.xingmu.tv.core.Result
import com.xingmu.tv.data.CanonicalCategory
import com.xingmu.tv.data.Channel
import com.xingmu.tv.data.HomeFeedRepository
import com.xingmu.tv.data.VodSummary
import com.xingmu.tv.data.WatchProgress
import com.xingmu.tv.data.WatchProgressStore
import com.xingmu.tv.search.PinyinIndex
import com.xingmu.tv.source.SourceRepository
import com.xingmu.tv.ui.LocalTvColors
import com.xingmu.tv.ui.LocalTvDimens
import com.xingmu.tv.ui.LocalTvTier
import com.xingmu.tv.ui.TvMode
import com.xingmu.tv.ui.components.LiteListRow
import com.xingmu.tv.ui.components.LocalListScrolling
import com.xingmu.tv.ui.components.PosterImage
import com.xingmu.tv.ui.components.PosterRow
import com.xingmu.tv.ui.components.TvButton
import com.xingmu.tv.ui.components.TvEmptyState
import com.xingmu.tv.ui.components.TvErrorState
import com.xingmu.tv.ui.components.TvLabel
import com.xingmu.tv.ui.components.DividerLine
import com.xingmu.tv.ui.components.TvFocusable
import com.xingmu.tv.ui.components.TvLoading
import com.xingmu.tv.ui.components.TvSkeletonRows
import com.xingmu.tv.ui.tvHeroHeight
import com.xingmu.tv.ui.tvPosterWidth
import com.xingmu.tv.ui.tvWidthDp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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
    onOpenLive: () -> Unit = {},
) {
    var reload by remember { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf<HomeUi>(HomeUi.Loading) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    val context = LocalContext.current
    // t32：热度候选池 + 30 分钟文件缓存（比 t4 的 LocalSourceRepository 缓存更高一层，只服务首页）
    val feedRepository = remember(context) { HomeFeedRepository(context) }

    LaunchedEffect(reload) {
        ui = HomeUi.Loading
        val progress = withContext(AppDispatchers.Io) { progressStore.list() }.take(12)
        channels = repo.listChannels().getOrNull().orEmpty()

        // 1) 冷启动先读热度缓存：命中立即渲染 hero/行，不等网络（首屏预算 ≤1.5s）
        val cached = feedRepository.cached()
        if (cached != null && cached.rows.isNotEmpty()) {
            val cachedRows = cached.rows.map { HomeRow(it.title, it.typeId, it.items) }
            pinyin.index(cachedRows.flatMap { it.items })
            ui = HomeUi.Ready(cachedRows, cached.hero, progress)
        }

        // 2) 后台刷新候选池（不阻塞首屏）；无缓存时先用旧的 4 行逻辑兜底
        val heatApplied = AtomicBoolean(false)
        launch {
            when (val feed = feedRepository.refresh(repo)) {
                is Result.Ok -> {
                    val rows = feed.value.rows.map { HomeRow(it.title, it.typeId, it.items) }
                    if (rows.isNotEmpty()) {
                        pinyin.index(rows.flatMap { it.items })
                        val fallbackHero = (ui as? HomeUi.Ready)?.hero
                        ui = HomeUi.Ready(
                            rows = rows,
                            hero = feed.value.hero ?: fallbackHero,
                            progress = progress,
                        )
                        heatApplied.set(true)
                    }
                }
                is Result.Err -> Unit // 候选池失败：保留缓存/兜底内容，不打扰用户
            }
        }
        if (cached == null || cached.rows.isEmpty()) {
            val (rows, errors) = loadFallbackRows(repo)
            if (!heatApplied.get()) {
                val all = rows.flatMap { it.items }
                pinyin.index(all)
                ui = if (rows.all { it.items.isEmpty() } && errors.isNotEmpty()) {
                    HomeUi.Failed(errors.first())
                } else {
                    HomeUi.Ready(rows, all.firstOrNull(), progress)
                }
            }
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
            LiteHome(state, channels, onOpenDetail, onOpenLive)
        } else {
            CinemaHome(state, onOpenDetail, onOpenCategory)
        }
    }
}

/** 无热度缓存时的兜底：沿用旧逻辑（4 个分类 page1 并发），保证首屏不等候选池。 */
private suspend fun loadFallbackRows(repo: SourceRepository): Pair<List<HomeRow>, List<AppError>> =
    coroutineScope {
        val definitions = listOf(
            CanonicalCategory.MOVIE to "热门电影",
            CanonicalCategory.TV to "最新剧集",
            CanonicalCategory.ANIME to "动漫热播",
            CanonicalCategory.VARIETY to "综艺更新",
        )
        val results = definitions.map { (typeId, _) ->
            async { typeId to repo.list(typeId, 1) }
        }.awaitAll()
        val rows = results.map { (typeId, result) ->
            HomeRow(
                title = definitions.first { it.first == typeId }.second,
                typeId = typeId,
                items = result.getOrNull().orEmpty(),
            )
        }
        rows to results.mapNotNull { it.second.errorOrNull() }
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
                onMore = if (row.items.isNotEmpty() && row.typeId > 0) {
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
        // t32：hero 右上角「🔥 N 源热播」角标（N = 跨源重合度）
        if (hero.sourceCount >= 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .background(colors.accent.copy(alpha = 0.92f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                TvLabel(
                    "🔥 ${hero.sourceCount} 源热播",
                    dimens.caption,
                    colors.accentOn,
                    weight = FontWeight.Bold,
                )
            }
        }
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
private fun LiteHome(
    state: HomeUi.Ready,
    channels: List<Channel>,
    onOpenDetail: (VodSummary) -> Unit,
    onOpenLive: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    // t28：派生列表 remember 住，避免每次重组都 flatMap 出新 List（低端机上就是滚动抖动源）
    val flat = remember(state.rows) { state.rows.flatMap { row -> row.items.map { row.title to it } } }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val resume = state.progress.firstOrNull()
    val featured = resume?.let { p -> state.rows.flatMap { it.items }.firstOrNull { it.vodId == p.vodId } }
        ?: state.hero
        ?: flat.firstOrNull()?.second
    val liveChannels = remember(channels) { channels.take(4) }

    Column(Modifier.fillMaxSize()) {
        // ── 顶部条：星幕 · 保命模式 ──────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TvLabel("星幕 · 保命模式", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
            Spacer(Modifier.width(1.dp).weight(1f))
            TvLabel("源 ${state.rows.count { it.items.isNotEmpty() }} · 直播 ${channels.size} 台", dimens.caption, colors.textMuted)
        }
        DividerLine()

        // ── 推荐条：240×135 + 继续观看 ─────────────────────────────────
        if (featured != null) {
            Spacer(Modifier.height(8.dp))
            TvFocusable(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenDetail(featured) },
            ) { focused ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (focused) colors.surfaceHigh else colors.bg)
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PosterImage(
                        url = featured.pic,
                        thumbUrl = featured.picThumb,
                        modifier = Modifier.size(width = 240.dp, height = 135.dp),
                        requestWidth = 240,
                        requestHeight = 135,
                        fallbackText = featured.name,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        TvLabel(
                            featured.name,
                            dimens.cardTitle,
                            if (focused) colors.accent else colors.text,
                            weight = FontWeight.Medium,
                            maxLines = 2,
                        )
                        TvLabel(
                            if (resume != null) "▶ 继续观看 第 ${resume.episodeIndex} 集" else "▶ 播放第 1 集",
                            dimens.caption,
                            colors.accent,
                            maxLines = 1,
                        )
                    }
                }
            }
            DividerLine()
        }

        // ── 最新更新：两列文字列表（行高 96dp，缩略图 72×96）─────────────
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvLabel("最新更新", dimens.cardTitle, colors.text, weight = FontWeight.Bold)
            Spacer(Modifier.width(1.dp).weight(1f))
            TvLabel("${flat.size} 条", dimens.caption, colors.textMuted)
        }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalListScrolling provides gridState.isScrollInProgress,
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 8.dp, end = 40.dp),
            ) {
                items(
                    items = flat,
                    key = { "${it.second.sourceId}|${it.second.vodId}" },
                    contentType = { "lite-row" },
                ) { (badge, vod) ->
                    LiteListRow(
                        vod = vod,
                        onClick = { onOpenDetail(vod) },
                        badge = badge,
                        thumbWidth = 72.dp,
                        thumbHeight = 96.dp,
                        rowHeight = 96.dp,
                    )
                }
            }
        }

        if (flat.isEmpty()) {
            TvEmptyState("暂无内容，检查网络后重试")
        }

        // ── 直播：一行 4 台（OK 进直播页）──────────────────────────────
        if (liveChannels.isNotEmpty()) {
            DividerLine()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TvLabel("直播", dimens.caption, colors.textMuted, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                liveChannels.forEach { channel ->
                    TvFocusable(
                        modifier = Modifier.width(150.dp),
                        onClick = onOpenLive,
                    ) { focused ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (focused) colors.surfaceHigh else colors.bg)
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TvLabel(channel.name.take(1), dimens.caption, colors.accent, weight = FontWeight.Bold, maxLines = 1)
                            Spacer(Modifier.width(6.dp))
                            TvLabel(
                                channel.name,
                                dimens.caption,
                                if (focused) colors.accent else colors.textSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }

        // ── 底部状态栏 ────────────────────────────────────────────────
        DividerLine()
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TvLabel("硬解 H.264 · 缓存就绪", dimens.caption, colors.textMuted, maxLines = 1)
            Spacer(Modifier.width(1.dp).weight(1f))
            TvLabel("OK 打开 · 返回 退出", dimens.caption, colors.textMuted, maxLines = 1)
        }
        DpadDownSink()
    }
}
