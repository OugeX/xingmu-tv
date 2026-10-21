package com.tvbox.app.ui.detail

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvbox.app.core.AppError
import com.tvbox.app.ui.DpadDownSink
import com.tvbox.app.core.Result
import android.os.SystemClock
import com.tvbox.app.core.TvLog
import com.tvbox.app.data.Episode
import com.tvbox.app.data.VodDetail
import com.tvbox.app.data.VodSummary
import com.tvbox.app.data.WatchProgressStore
import com.tvbox.app.search.PinyinIndex
import com.tvbox.app.source.SourceRepository
import com.tvbox.app.ui.LocalTvColors
import com.tvbox.app.ui.LocalTvDimens
import com.tvbox.app.ui.LocalTvMode
import com.tvbox.app.ui.TvMode
import com.tvbox.app.ui.components.PosterImage
import com.tvbox.app.ui.components.TvButton
import com.tvbox.app.ui.components.TvChip
import com.tvbox.app.ui.components.TvEmptyState
import com.tvbox.app.ui.components.TvErrorState
import com.tvbox.app.ui.components.TvFocusable
import com.tvbox.app.ui.components.TvLabel
import com.tvbox.app.ui.components.TvLoading
import com.tvbox.app.ui.player.PlaybackRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    vodId: String,
    sourceId: String,
    fallbackTitle: String,
    repo: SourceRepository,
    pinyin: PinyinIndex,
    progressStore: WatchProgressStore,
    mode: TvMode,
    onPlay: (PlaybackRequest) -> Unit,
    onBack: () -> Unit,
) {
    var detail by remember { mutableStateOf<VodDetail?>(null) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var pendingPlay by remember { mutableStateOf(false) }
    val loadingFocus = remember { FocusRequester() }

    // 深链打开详情时，焦点默认会落在左侧导航（ENTER 会误触「推荐」回首页）；
    // 加载态主动把焦点抢到内容区，保证 OK 是“播放”而不是“切导航”。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        val ok = runCatching { loadingFocus.requestFocus() }.isSuccess
        TvLog.i("DetailDeepLink", "loadingFocus=$ok")
    }

    LaunchedEffect(vodId, sourceId, reload) {
        val startedAt = SystemClock.elapsedRealtime()
        loading = true
        error = null
        when (val result = repo.getDetail(vodId, sourceId)) {
            is Result.Ok -> {
                detail = result.value
                TvLog.i(
                    "DetailPerf",
                    "ui loaded in=${SystemClock.elapsedRealtime() - startedAt}ms vod=$vodId source=$sourceId name=${result.value.name}",
                )
                TvLog.i("DetailDeepLink", "request=($vodId,$sourceId) loaded=(${result.value.vodId},${result.value.sourceId}) name=${result.value.name}")
                pinyin.index(listOf(result.value.toSummary()))
            }
            is Result.Err -> {
                error = result.error
                detail = null
            }
        }
        loading = false
    }

    // 深链/加载中按 OK：记下来，数据到后直接播第 1 集（避免“按了没反应”）
    LaunchedEffect(detail, pendingPlay) {
        if (pendingPlay && detail != null) {
            val loaded = detail!!
            val from = loaded.playFroms.firstOrNull()
            val episodes = loaded.episodesByFrom[from].orEmpty()
            val first = episodes.firstOrNull()
            pendingPlay = false
            if (first != null) {
                onPlay(
                    PlaybackRequest(
                        vodId = loaded.vodId,
                        sourceId = loaded.sourceId,
                        vodName = loaded.name,
                        year = loaded.year,
                        playFrom = from.orEmpty(),
                        episodeIndex = first.index,
                        episodes = episodes,
                    ),
                )
            }
        }
    }

    val current = detail
    when {
        loading && current == null -> Box(
            Modifier
                .fillMaxSize()
                .focusRequester(loadingFocus)
                .focusable()
                .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    pendingPlay = true
                    true
                } else {
                    false
                }
            },
        ) {
            TvLoading("正在打开《$fallbackTitle》…（按 OK 加载后直接播放第 1 集）")
        }
        error != null && current == null -> TvErrorState(error = error!!, onRetry = { reload++ }, extraActionText = "返回", onExtraAction = onBack)
        current == null -> TvErrorState(AppError("E_NULL", "详情为空"), onRetry = { reload++ }, extraActionText = "返回", onExtraAction = onBack)
        else -> DetailBody(
            detail = current,
            repo = repo,
            pinyin = pinyin,
            progressStore = progressStore,
            mode = mode,
            onPlay = onPlay,
            onBack = onBack,
            onReplaceDetail = { detail = it },
        )
    }
}

private fun VodDetail.toSummary(): VodSummary = VodSummary(
    vodId = vodId,
    sourceId = sourceId,
    name = name,
    pic = pic,
    remarks = remarks,
    year = year,
    typeId = "",
    typeName = typeName,
    score = score,
)

@Composable
private fun DetailBody(
    detail: VodDetail,
    repo: SourceRepository,
    pinyin: PinyinIndex,
    progressStore: WatchProgressStore,
    mode: TvMode,
    onPlay: (PlaybackRequest) -> Unit,
    onBack: () -> Unit,
    onReplaceDetail: (VodDetail) -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(detail.vodId, detail.sourceId) {
        kotlinx.coroutines.delay(80)
        val ok = runCatching { playFocus.requestFocus() }.isSuccess
        TvLog.i("DetailDeepLink", "playFocus=$ok vod=${detail.vodId}")
    }

    val initialProgress = remember(detail.vodId) { progressStore.lastFor(detail.sourceId, detail.vodId) }
    var selectedIndex by remember(detail.vodId) { mutableIntStateOf(initialProgress?.episodeIndex ?: 1) }
    var selectedFrom by remember(detail.sourceId) {
        mutableStateOf(initialProgress?.playFrom?.takeIf { it.isNotBlank() } ?: detail.playFroms.firstOrNull().orEmpty())
    }
    var expanded by remember(detail.vodId) { mutableStateOf(false) }
    var reversed by remember { mutableStateOf(false) }
    var showSourcePanel by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var alternates by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var loadingAlternates by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var altAvailability by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    val activeFrom = remember(detail.sourceId, detail.playFroms, selectedFrom) {
        if (selectedFrom.isNotBlank() && detail.episodesByFrom.containsKey(selectedFrom)) {
            selectedFrom
        } else {
            detail.playFroms.firstOrNull().orEmpty()
        }
    }

    val watched = remember(detail.vodId, detail.sourceId) {
        progressStore.watchedEpisodes(detail.sourceId, detail.vodId)
    }

    fun requestFor(index: Int): PlaybackRequest? {
        val episodes = detail.episodesByFrom[activeFrom].orEmpty()
        val episode = episodes.firstOrNull { it.index == index } ?: return null
        return PlaybackRequest(
            vodId = detail.vodId,
            sourceId = detail.sourceId,
            vodName = detail.name,
            playFrom = activeFrom,
            episodeIndex = episode.index,
            episodes = episodes,
            startPositionMs = if (initialProgress?.episodeIndex == index) initialProgress.positionMs else 0L,
        )
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(3_500)
            message = null
        }
    }

    LaunchedEffect(showSourcePanel) {
        if (!showSourcePanel) return@LaunchedEffect
        if (alternates.isNotEmpty()) return@LaunchedEffect
        loadingAlternates = true
        when (val result = repo.findAlternateSources(detail.vodId, detail.name, detail.year)) {
            is Result.Ok -> alternates = result.value
            is Result.Err -> message = result.error.display()
        }
        loadingAlternates = false
    }

    LaunchedEffect(showSourcePanel, alternates, selectedIndex) {
        if (!showSourcePanel || alternates.isEmpty()) return@LaunchedEffect
        val availability = coroutineScope {
            alternates.take(6).map { alt ->
                async {
                    val target = repo.getDetail(alt.vodId, alt.sourceId).getOrNull()
                    val hasIndex = target?.let { candidate ->
                        val firstFrom = candidate.playFroms.firstOrNull()
                        candidate.episodesByFrom[firstFrom].orEmpty().any { it.index == selectedIndex }
                    } ?: true
                    alt.sourceId to hasIndex
                }
            }.awaitAll()
        }
        altAvailability = availability.toMap()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "hero") {
                DetailHero(detail = detail, mode = mode, screenHeightDp = screenHeight)
            }

            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvButton("‹ 返回", onClick = onBack)
                    TvButton(
                        text = "▶ 播放第 ${selectedIndex} 集",
                        onClick = {
                            val request = requestFor(selectedIndex)
                            if (request != null) onPlay(request) else message = "当前线路缺第 $selectedIndex 集"
                        },
                        primary = true,
                        modifier = Modifier.focusRequester(playFocus),
                    )
                    TvButton("切换源（${detail.sourceId}）", onClick = { showSourcePanel = true })
                    TvButton("标记已看", onClick = {
                        progressStore.markWatched(detail.sourceId, detail.vodId, selectedIndex)
                        message = "已标记第 $selectedIndex 集为已看"
                    })
                }
            }

            if (message != null) {
                item(key = "message") {
                    TvLabel(message.orEmpty(), dimens.body, colors.accent, maxLines = 2)
                }
            }

            item(key = "meta") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvLabel(detail.name, dimens.rowTitle, colors.text, weight = FontWeight.Bold, maxLines = 2)
                    TvLabel(
                        listOfNotNull(
                            detail.typeName.takeIf { it.isNotBlank() },
                            detail.year.takeIf { it.isNotBlank() },
                            detail.area.takeIf { it.isNotBlank() },
                            detail.lang.takeIf { it.isNotBlank() },
                            detail.remarks.takeIf { it.isNotBlank() },
                            detail.score.takeIf { it.isNotBlank() && it != "0.0" }?.let { "评分 $it" },
                        ).joinToString(" · "),
                        dimens.body,
                        colors.textSecondary,
                        maxLines = 2,
                    )
                    if (detail.actors.isNotBlank()) {
                        TvLabel("演员：${detail.actors}", dimens.caption, colors.textMuted, maxLines = 2)
                    }
                    if (detail.director.isNotBlank()) {
                        TvLabel("导演：${detail.director}", dimens.caption, colors.textMuted, maxLines = 1)
                    }
                }
            }

            item(key = "synopsis") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (detail.content.isNotBlank()) {
                        TvLabel(
                            text = detail.content,
                            fontSize = dimens.body,
                            color = colors.textSecondary,
                            maxLines = if (expanded) Int.MAX_VALUE else 4,
                        )
                        if (detail.content.length > 120) {
                            TvButton(if (expanded) "收起简介" else "展开简介", onClick = { expanded = !expanded })
                        }
                    } else {
                        TvLabel("暂无简介", dimens.caption, colors.textMuted)
                    }
                }
            }

            if (detail.playFroms.isNotEmpty()) {
                item(key = "lines") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvLabel("播放线路", dimens.cardTitle, colors.text, weight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(detail.playFroms, key = { it }, contentType = { "play-from" }) { from ->
                                TvChip(
                                    text = lineLabel(from),
                                    selected = activeFrom == from,
                                    onClick = {
                                        selectedFrom = from
                                        val first = detail.episodesByFrom[from]?.firstOrNull()
                                        if (first != null) selectedIndex = first.index
                                        message = "已选择 ${lineLabel(from)}"
                                    },
                                )
                            }
                        }
                    }
                }

                item(key = "episodes") {
                    EpisodeSection(
                        detail = detail,
                        selectedFrom = activeFrom,
                        selectedIndex = selectedIndex,
                        watched = watched,
                        reversed = reversed,
                        onToggleReverse = { reversed = !reversed },
                        onSelect = { episode ->
                            selectedIndex = episode.index
                            val request = requestFor(episode.index)
                            if (request != null) onPlay(request) else message = "该集地址无效"
                        },
                    )
                }
            } else {
                item(key = "no-lines") {
                    TvEmptyState("该作品暂无播放地址，换一个源试试")
                }
            }

            item(key = "focus-sink") { DpadDownSink() }

            item(key = "source-hint") {
                TvLabel(
                    "源：${detail.sourceId} · 同片源可在「切换源」里选，切换保持当前集号",
                    dimens.caption,
                    colors.textMuted,
                    maxLines = 2,
                )
            }
        }

        if (showSourcePanel) {
            SourcePanel(
                detail = detail,
                alternates = alternates,
                loading = loadingAlternates,
                switching = switching,
                currentIndex = selectedIndex,
                availability = altAvailability,
                onClose = { showSourcePanel = false },
                onPick = { candidate ->
                    if (switching) return@SourcePanel
                    switching = true
                    scope.launch {
                        when (val result = repo.getDetail(candidate.vodId, candidate.sourceId)) {
                            is Result.Ok -> {
                                val newDetail = result.value
                                val firstFrom = newDetail.playFroms.firstOrNull()
                                val targetEpisodes = newDetail.episodesByFrom[firstFrom].orEmpty()
                                val target = targetEpisodes.firstOrNull { it.index == selectedIndex }
                                if (target == null) {
                                    message = "《${candidate.name}》缺第 $selectedIndex 集，已留在原线路"
                                } else {
                                    pinyin.index(listOf(newDetail.toSummary()))
                                    onReplaceDetail(newDetail)
                                    showSourcePanel = false
                                    message = "已切换到《${candidate.name}》第 $selectedIndex 集"
                                }
                            }
                            is Result.Err -> message = "切换失败：${result.error.display()}"
                        }
                        switching = false
                    }
                },
            )
        }
    }
}

@Composable
private fun DetailHero(detail: VodDetail, mode: TvMode, screenHeightDp: Int) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val lite = mode == TvMode.LITE
    val heroHeight = if (lite) 150.dp else (screenHeightDp * 0.42f).coerceIn(220f, 460f).dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .clip(if (lite) RoundedCornerShape(0.dp) else RoundedCornerShape(10.dp)),
    ) {
        PosterImage(
            url = detail.slidePic.ifBlank { detail.pic },
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            requestWidth = 1280,
            requestHeight = 720,
        )
        if (!lite) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to colors.bg,
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                TvLabel(detail.name, dimens.heroTitle * 0.52f, colors.text, weight = FontWeight.Black, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EpisodeSection(
    detail: VodDetail,
    selectedFrom: String,
    selectedIndex: Int,
    watched: Set<Int>,
    reversed: Boolean,
    onToggleReverse: () -> Unit,
    onSelect: (Episode) -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val episodes = detail.episodesByFrom[selectedFrom].orEmpty()
    val ordered = remember(episodes, reversed) { if (reversed) episodes.reversed() else episodes }
    val paged = episodes.size > 60
    var page by remember(selectedFrom, episodes.size) { mutableIntStateOf(0) }
    val pageSize = if (paged) 30 else episodes.size.coerceAtLeast(1)
    val pageCount = if (paged) (episodes.size + pageSize - 1) / pageSize else 1
    // t28：页码列表 remember 住（原来每次重组都 (0 until n).toList() 新建对象）
    val pageIndices = remember(pageCount) { (0 until pageCount).toList() }
    val visible = remember(ordered, page, paged, pageSize) {
        if (paged) ordered.drop(page * pageSize).take(pageSize) else ordered
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvLabel(
                "选集（共 ${episodes.size} 集）· ${lineLabel(selectedFrom)}",
                dimens.cardTitle,
                colors.text,
                weight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(1.dp).weight(1f))
            TvChip("倒序", selected = reversed, onClick = onToggleReverse)
        }

        if (paged) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pageIndices, key = { it }, contentType = { "page" }) { index ->
                    val start = index * pageSize + 1
                    val end = ((index + 1) * pageSize).coerceAtMost(episodes.size)
                    TvChip(
                        text = if (reversed) {
                            "${episodes.size - index * pageSize}–${(episodes.size - (index + 1) * pageSize + 1).coerceAtLeast(1)}"
                        } else {
                            "$start–$end"
                        },
                        selected = page == index,
                        onClick = { page = index },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visible.chunked(8).forEach { chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chunk.forEach { episode ->
                        EpisodeChip(
                            episode = episode,
                            selected = episode.index == selectedIndex,
                            watched = episode.index in watched,
                            onClick = { onSelect(episode) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeChip(
    episode: Episode,
    selected: Boolean,
    watched: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val lite = LocalTvMode.current == TvMode.LITE
    TvFocusable(
        modifier = Modifier.width(118.dp),
        shape = if (lite) RoundedCornerShape(0.dp) else RoundedCornerShape(4.dp),
        onClick = onClick,
    ) { focused ->
        val background = when {
            selected -> colors.accent
            focused -> colors.surfaceHigh
            else -> colors.surface
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(background)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            TvLabel(
                text = (if (watched) "✓ " else "") + episode.title,
                fontSize = dimens.caption,
                color = if (selected) colors.accentOn else colors.textSecondary,
                maxLines = 1,
                weight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun SourcePanel(
    detail: VodDetail,
    alternates: List<VodSummary>,
    loading: Boolean,
    switching: Boolean,
    currentIndex: Int,
    availability: Map<String, Boolean>,
    onClose: () -> Unit,
    onPick: (VodSummary) -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .padding(28.dp),
    ) {
        Column(Modifier.fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvLabel("切换源 · ${detail.name}", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
                Spacer(Modifier.width(1.dp).weight(1f))
                TvButton("关闭", onClick = onClose)
            }
            Spacer(Modifier.height(6.dp))
            TvLabel(
                "目标源会保持第 $currentIndex 集；缺集的行置灰不可用。当前源：${detail.sourceId}",
                dimens.caption,
                colors.textMuted,
                maxLines = 2,
            )
            Spacer(Modifier.height(14.dp))
            when {
                loading -> TvLoading("正在聚合其它源…")
                alternates.isEmpty() -> TvEmptyState("没有找到其它源的同名片")
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    alternates.forEach { candidate ->
                        val missing = availability[candidate.sourceId] == false
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surface)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                TvLabel(candidate.name, dimens.cardTitle, colors.text, maxLines = 1)
                                TvLabel(
                                    if (missing) {
                                        "源：${candidate.sourceId} · ${candidate.year} · 缺第 $currentIndex 集"
                                    } else {
                                        "源：${candidate.sourceId} · ${candidate.year} · ${candidate.remarks}"
                                    },
                                    dimens.caption,
                                    if (missing) colors.textMuted else colors.textMuted,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            TvButton(
                                text = if (missing) "缺第 $currentIndex 集" else if (switching) "切换中…" else "切到该源",
                                enabled = !switching && !missing,
                                primary = true,
                                onClick = { onPick(candidate) },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

private fun lineLabel(from: String): String = when {
    from.isBlank() -> "默认线路"
    from.contains("bfzy", ignoreCase = true) -> "暴风线路"
    from.contains("ffzy", ignoreCase = true) -> "非凡线路"
    from.contains("dytt", ignoreCase = true) -> "电影天堂线路"
    from.contains("lzi", ignoreCase = true) -> "量子线路"
    from.contains("bdzy", ignoreCase = true) -> "百度线路"
    from.contains("guangsu", ignoreCase = true) -> "光速线路"
    from.contains("zuid", ignoreCase = true) -> "最大线路"
    else -> from
}
