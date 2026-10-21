package com.xingmu.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.xingmu.tv.core.AppError
import com.xingmu.tv.ui.DpadDownSink
import com.xingmu.tv.core.Result
import android.os.SystemClock
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.data.Episode
import com.xingmu.tv.data.VodDetail
import com.xingmu.tv.data.VodSummary
import com.xingmu.tv.data.WatchProgressStore
import com.xingmu.tv.search.PinyinIndex
import com.xingmu.tv.source.SourceRepository
import com.xingmu.tv.ui.LocalTvColors
import com.xingmu.tv.ui.LocalTvDimens
import com.xingmu.tv.ui.LocalTvMode
import com.xingmu.tv.ui.TvMode
import com.xingmu.tv.ui.components.PosterImage
import com.xingmu.tv.ui.components.TvButton
import com.xingmu.tv.ui.components.TvChip
import com.xingmu.tv.ui.components.TvEmptyState
import com.xingmu.tv.ui.components.TvErrorState
import com.xingmu.tv.ui.components.TvFocusable
import com.xingmu.tv.ui.components.TvLabel
import com.xingmu.tv.ui.components.TvLoading
import com.xingmu.tv.ui.player.PlaybackRequest
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
    /** t29：从分类页进入时带上分类 id，用于底部「同分类推荐」；深链/首页进入为 null。 */
    categoryTypeId: Int? = null,
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
            categoryTypeId = categoryTypeId,
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
    categoryTypeId: Int?,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()
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

    // t29：收藏 / 更多面板 / 集数直达 / 同分类推荐
    val context = androidx.compose.ui.platform.LocalContext.current
    val favorites = remember { com.xingmu.tv.ui.FavoritesStore(context) }
    var isFavorite by remember(detail.sourceId, detail.vodId) {
        mutableStateOf(favorites.isFavorite(detail.sourceId, detail.vodId))
    }
    var showMore by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var similar by remember(detail.vodId) { mutableStateOf<List<VodSummary>>(emptyList()) }

    // 同分类推荐：复用 list(typeId, 1)，取 8 部并排除自己（UI_TUNE §2.3）
    LaunchedEffect(categoryTypeId, detail.vodId) {
        val typeId = categoryTypeId ?: return@LaunchedEffect
        when (val result = repo.list(typeId, 1)) {
            is Result.Ok -> similar = result.value
                .filter { !(it.vodId == detail.vodId && it.sourceId == detail.sourceId) }
                .take(8)
            is Result.Err -> similar = emptyList()
        }
    }

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
            // ── 首屏信息重排（t29 / UI_TUNE §2.1）：左海报 + 右信息，不滚动就能看全 ──
            item(key = "head") {
                DetailHead(
                    detail = detail,
                    mode = mode,
                    sourceLabel = sourceLabel(detail.sourceId),
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                )
            }

            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 顺序：播放第 N 集 → 收藏 → 切换源(N) → 更多
                    TvButton(
                        text = "▶ 播放第 ${selectedIndex} 集",
                        onClick = {
                            val request = requestFor(selectedIndex)
                            if (request != null) onPlay(request) else message = "当前线路缺第 $selectedIndex 集"
                        },
                        primary = true,
                        modifier = Modifier.focusRequester(playFocus),
                    )
                    TvButton(
                        text = if (isFavorite) "✓ 已收藏" else "＋ 收藏",
                        onClick = {
                            isFavorite = favorites.toggle(detail.sourceId, detail.vodId)
                            message = if (isFavorite) "已加入收藏" else "已取消收藏"
                        },
                    )
                    TvButton(
                        text = "切换源（${if (alternates.isNotEmpty()) alternates.size + 1 else 1}）",
                        onClick = { showSourcePanel = true },
                    )
                    TvButton("更多", onClick = { showMore = true })
                    Spacer(Modifier.width(1.dp).weight(1f))
                    TvButton("‹ 返回", onClick = onBack)
                }
            }

            if (message != null) {
                item(key = "message") {
                    TvLabel(message.orEmpty(), dimens.body, colors.accent, maxLines = 2)
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
                        lite = mode == TvMode.LITE,
                        onToggleReverse = { reversed = !reversed },
                        onJumpRequest = { showJump = true },
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

            // t29：同分类推荐（复用 list(typeId,1)，取 8 部，排除自己）
            if (similar.isNotEmpty()) {
                item(key = "similar") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvLabel("同分类推荐", dimens.cardTitle, colors.text, weight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(
                                items = similar,
                                key = { "${it.sourceId}|${it.vodId}" },
                                contentType = { "similar" },
                            ) { vod ->
                                com.xingmu.tv.ui.components.PosterCard(
                                    vod = vod,
                                    onClick = {
                                        scope.launch {
                                            pinyin.index(listOf(vod))
                                            when (val result = repo.getDetail(vod.vodId, vod.sourceId)) {
                                                is Result.Ok -> {
                                                    onReplaceDetail(result.value)
                                                    message = "已打开《${result.value.name}》"
                                                }
                                                is Result.Err -> message = "打开失败：${result.error.display()}"
                                            }
                                        }
                                    },
                                    width = 132.dp,
                                )
                            }
                        }
                    }
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

        // t29：「更多」面板（标记已看 / 复制片名 / 刷新详情 / 收藏数）
        if (showMore) {
            MoreSheet(
                favoriteCount = favorites.count(),
                onMarkWatched = {
                    progressStore.markWatched(detail.sourceId, detail.vodId, selectedIndex)
                    message = "已标记第 $selectedIndex 集为已看"
                    showMore = false
                },
                onCopyName = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("片名", detail.name))
                    message = "已复制片名"
                    showMore = false
                },
                onReload = {
                    showMore = false
                    onReplaceDetail(detail)
                },
                onClearFavorites = {
                    message = "收藏为本地记录，当前 ${favorites.count()} 部"
                    showMore = false
                },
                onClose = { showMore = false },
            )
        }

        // t29：跳到第 N 集（数字键盘浮层，遥控器输 3 位）
        if (showJump) {
            JumpSheet(
                total = detail.episodesByFrom[activeFrom].orEmpty().size,
                onJump = { target ->
                    val episodes = detail.episodesByFrom[activeFrom].orEmpty()
                    val episode = episodes.firstOrNull { it.index == target }
                    showJump = false
                    if (episode == null) {
                        message = "没有第 $target 集（共 ${episodes.size} 集）"
                    } else {
                        selectedIndex = episode.index
                        val request = requestFor(episode.index)
                        if (request != null) onPlay(request) else message = "该集地址无效"
                    }
                },
                onClose = { showJump = false },
            )
        }
    }
}

/** t29：首屏信息区——左海报 300×420（C 180×252）+ 右标题/元信息/演员/简介。 */
@Composable
private fun DetailHead(
    detail: VodDetail,
    mode: TvMode,
    sourceLabel: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val lite = mode == TvMode.LITE
    val posterWidth = if (lite) 180.dp else 300.dp
    val posterHeight = if (lite) 252.dp else 420.dp

    Row(horizontalArrangement = Arrangement.spacedBy(if (lite) 14.dp else 20.dp)) {
        // 封面兜底：PosterImage 失败时用片名首字占位，绝不留黑块
        Box(
            Modifier
                .width(posterWidth)
                .height(posterHeight)
                .clip(if (lite) RoundedCornerShape(0.dp) else RoundedCornerShape(6.dp)),
        ) {
            PosterImage(
                url = detail.pic.ifBlank { detail.slidePic },
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                requestWidth = posterWidth.value.toInt(),
                requestHeight = posterHeight.value.toInt(),
                fallbackText = detail.name,
            )
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TvLabel(detail.name, dimens.rowTitle, colors.text, weight = FontWeight.Bold, maxLines = 2)
            TvLabel(
                listOfNotNull(
                    detail.score.takeIf { it.isNotBlank() && it != "0.0" }?.let { "评分 $it" },
                    detail.year.takeIf { it.isNotBlank() },
                    detail.area.takeIf { it.isNotBlank() },
                    detail.duration.takeIf { it.isNotBlank() } ?: detail.remarks.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                dimens.body,
                colors.textSecondary,
                maxLines = 1,
            )
            if (detail.actors.isNotBlank()) {
                TvLabel("演员：${detail.actors}", dimens.caption, colors.textMuted, maxLines = 1)
            }
            if (detail.content.isNotBlank()) {
                TvLabel(
                    text = detail.content,
                    fontSize = dimens.body,
                    color = colors.textSecondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                )
                if (detail.content.length > 90 || expanded) {
                    TvButton(if (expanded) "收起简介" else "展开简介", onClick = onToggleExpanded)
                }
            } else {
                TvLabel("暂无简介", dimens.caption, colors.textMuted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvLabel("源：$sourceLabel", dimens.caption, colors.textMuted, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                TvLabel(detail.typeName.ifBlank { "点播" }, dimens.caption, colors.accent, maxLines = 1)
            }
        }
    }
}

/** t29：「更多」面板。 */
@Composable
private fun MoreSheet(
    favoriteCount: Int,
    onMarkWatched: () -> Unit,
    onCopyName: () -> Unit,
    onReload: () -> Unit,
    onClearFavorites: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(460.dp)
                .background(colors.surface)
                .border(1.dp, colors.divider)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvLabel("更多", dimens.cardTitle, colors.text, weight = FontWeight.SemiBold, maxLines = 1)
            TvButton("标记第 N 集已看", onClick = onMarkWatched)
            TvButton("复制片名", onClick = onCopyName)
            TvButton("刷新详情", onClick = onReload)
            TvLabel("本地收藏：$favoriteCount 部", dimens.caption, colors.textMuted, maxLines = 1)
            TvButton("收藏说明", onClick = onClearFavorites)
            TvButton("关闭", onClick = onClose, primary = true)
        }
    }
}

/** t29：跳到第 N 集（数字键盘，遥控器友好；输满 3 位或按确定跳转）。 */
@Composable
private fun JumpSheet(total: Int, onJump: (Int) -> Unit, onClose: () -> Unit) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    var digits by remember { mutableStateOf("") }

    fun submit() {
        val target = digits.toIntOrNull()
        if (target == null || target <= 0) return
        onJump(target)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(420.dp)
                .background(colors.surface)
                .border(1.dp, colors.divider)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvLabel("跳到第几集（共 $total 集）", dimens.cardTitle, colors.text, weight = FontWeight.SemiBold, maxLines = 1)
            TvLabel(digits.ifBlank { "—" }, dimens.rowTitle, colors.accent, weight = FontWeight.Bold, maxLines = 1)
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("删除", "0", "跳转")).forEach { rowKeys ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowKeys.forEach { key ->
                        TvButton(
                            text = key,
                            onClick = {
                                when (key) {
                                    "删除" -> digits = digits.dropLast(1)
                                    "跳转" -> submit()
                                    else -> if (digits.length < 3) digits += key
                                }
                            },
                        )
                    }
                }
            }
            TvButton("取消", onClick = onClose)
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
    onJumpRequest: () -> Unit,
    onSelect: (Episode) -> Unit,
    lite: Boolean = false,
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
            // t29：集数直达（长剧友好）
            TvButton("跳到第 __ 集", onClick = onJumpRequest)
            Spacer(Modifier.width(8.dp))
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
            visible.chunked(if (lite) 6 else 8).forEach { chunk ->
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

/** t29：源显示名（bfzy1 → 暴风；未知源原样显示）。 */
private fun sourceLabel(sourceId: String): String = when (sourceId) {
    "bfzy1" -> "暴风"
    "ffzy" -> "非凡"
    else -> sourceId
}
