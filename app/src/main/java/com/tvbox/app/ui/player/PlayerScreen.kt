package com.tvbox.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tvbox.app.core.Result
import com.tvbox.app.core.TvLog
import com.tvbox.app.data.MediaUrl
import com.tvbox.app.data.VodDetail
import com.tvbox.app.data.VodSummary
import com.tvbox.app.data.WatchProgress
import com.tvbox.app.data.WatchProgressStore
import com.tvbox.app.player.PlaybackMemoryGuard
import com.tvbox.app.player.PlayerBootstrap
import com.tvbox.app.player.PlayerController
import com.tvbox.app.player.PlayerControllerImpl
import com.tvbox.app.player.VideoSurface
import com.tvbox.app.source.SourceRepository
import com.tvbox.app.ui.LocalTvColors
import com.tvbox.app.ui.LocalTvDimens
import com.tvbox.app.ui.settings.SettingsStore
import com.tvbox.app.ui.LocalTvTier
import com.tvbox.app.ui.components.TvButton
import com.tvbox.app.ui.components.TvEmptyState
import com.tvbox.app.ui.components.TvLabel
import com.tvbox.app.ui.components.TvLoading
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private val QUALITIES = listOf(0 to "自动", 1080 to "1080p", 720 to "720p", 480 to "480p")

private data class PendingSwitch(
    val previous: PlaybackRequest,
    val previousPosition: Long,
    val target: PlaybackRequest,
)

/**
 * 播放页 UI 外壳（t4）：只通过冻结的 [PlayerController] 与 [SourceRepository] 工作。
 * 播放器内核由 t9 提供；未注册时页面仍可导航并显示提示，不会崩溃。
 */
@Composable
fun PlayerScreen(
    request: PlaybackRequest,
    repo: SourceRepository,
    progressStore: WatchProgressStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()
    val controller: PlayerControllerImpl = remember { PlayerBootstrap.createAndRegister(context) }
    val appSettings = remember { SettingsStore(context).read() }
    val state by controller.state.collectAsState()
    val position by controller.positionMs.collectAsState()
    val duration by controller.durationMs.collectAsState()

    var current by remember { mutableStateOf(request) }
    var episodeIndex by remember { mutableIntStateOf(request.episodeIndex) }
    var hudVisible by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var qualityIndex by remember { mutableIntStateOf(0) }
    var audioDelay by remember { mutableIntStateOf(0) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingSwitch by remember { mutableStateOf<PendingSwitch?>(null) }
    var alternates by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var altAvailability by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var loadingAlternates by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }

    BackHandler(enabled = showEpisodes || showSources) {
        if (showEpisodes) {
            showEpisodes = false
        } else if (showSources) {
            showSources = false
        }
    }

    fun mediaOf(req: PlaybackRequest): MediaUrl? {
        val episode = req.episodeAt(req.episodeIndex) ?: return null
        return MediaUrl(label = episode.title, url = episode.url)
    }

    fun startPlay(req: PlaybackRequest, startMs: Long, isSwitch: Boolean = true) {
        val media = mediaOf(req)
        if (media == null) {
            message = "第 ${req.episodeIndex} 集没有可用地址"
            return
        }
        if (isSwitch) {
            pendingSwitch = PendingSwitch(previous = current, previousPosition = position, target = req)
        }
        current = req
        episodeIndex = req.episodeIndex
        controller.play(media, startMs)
    }

    LaunchedEffect(current.vodId, current.sourceId, episodeIndex) {
        // t22：把该集所有线路注册给播放器，首片 4s 未到自动切下一条（HUD 提示）
        val detail = (repo.getDetail(current.vodId, current.sourceId) as? com.tvbox.app.core.Result.Ok)?.value
            ?: return@LaunchedEffect
        val lines = ArrayList<MediaUrl>()
        detail.playFroms.sortedBy { if (it == current.playFrom) 0 else 1 }.forEach { from ->
            detail.episodesByFrom[from]
                ?.firstOrNull { it.index == episodeIndex }
                ?.let { ep -> lines += MediaUrl(label = "$from · ${ep.title}", url = ep.url) }
        }
        if (lines.isNotEmpty()) controller.setFallbackSources(lines)
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        controller.setQuality(appSettings.qualityCap)
        controller.setAudioDelayMs(appSettings.audioDelayMs)
        startPlay(current, current.startPositionMs, isSwitch = false)
    }

    LaunchedEffect(hudVisible, state) {
        if (!hudVisible) return@LaunchedEffect
        delay(3_000)
        if (state is PlayerController.State.Playing) hudVisible = false
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(3_500)
            message = null
        }
    }

    // 切集/换源：READY 后才提交；ERROR 回到旧线路（PLAYBACK_SPEC §3.3 / EPISODE_SPEC §3.3）
    LaunchedEffect(state) {
        val pending = pendingSwitch ?: return@LaunchedEffect
        when (state) {
            is PlayerController.State.Ready, is PlayerController.State.Playing -> {
                pendingSwitch = null
                message = "已切换到第 ${pending.target.episodeIndex} 集"
                saveProgress(pending.target, progressStore, position)
            }
            is PlayerController.State.Error -> {
                pendingSwitch = null
                message = "切换失败，已留在原线路"
                val previousMedia = mediaOf(pending.previous) ?: return@LaunchedEffect
                current = pending.previous
                episodeIndex = pending.previous.episodeIndex
                controller.play(previousMedia, pending.previousPosition)
            }
            else -> Unit
        }
    }

    DisposableEffect(controller) {
        // t25 / P1-7：点播播放态同样让位（Coil 上限压到 8MB、暂停海报请求）
        PlaybackMemoryGuard.enter(context)
        onDispose {
            PlaybackMemoryGuard.exit()
            saveProgress(current, progressStore, position)
            PlayerBootstrap.release(controller)
        }
    }

    LaunchedEffect(showSources) {
        if (!showSources) return@LaunchedEffect
        if (alternates.isEmpty()) {
            loadingAlternates = true
            when (val result = repo.findAlternateSources(current.vodId, current.vodName, current.year)) {
                is Result.Ok -> alternates = result.value
                is Result.Err -> message = result.error.display()
            }
            loadingAlternates = false
        }
        if (alternates.isNotEmpty() && altAvailability.isEmpty()) {
            altAvailability = coroutineScope {
                alternates.take(6).map { alt ->
                    async {
                        val detail = repo.getDetail(alt.vodId, alt.sourceId).getOrNull()
                        val has = detail?.let { d ->
                            d.episodesByFrom[d.playFroms.firstOrNull()].orEmpty().any { it.index == episodeIndex }
                        } ?: true
                        alt.sourceId to has
                    }
                }.awaitAll().toMap()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                hudVisible = true
                if (event.type == KeyEventType.KeyDown &&
                    event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_MENU
                ) {
                    showEpisodes = true
                    true
                } else if (event.type == KeyEventType.KeyDown &&
                    event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_INFO
                ) {
                    showSources = true
                    true
                } else {
                    false
                }
            },
    ) {
        VideoSurface(controller = controller, modifier = Modifier.fillMaxSize())

        if (state !is PlayerController.State.Playing && state !is PlayerController.State.Ready) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TvLabel(
                text = "正在播放",
                fontSize = dimens.rowTitle,
                color = colors.text,
                weight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            TvLabel(
                "${current.vodName} · 第 $episodeIndex 集 · ${current.playFrom}",
                dimens.body,
                colors.textSecondary,
                maxLines = 2,
            )
            Spacer(Modifier.height(6.dp))
            TvLabel(
                when (state) {
                    is PlayerController.State.Error -> "错误：${(state as PlayerController.State.Error).message}"
                    is PlayerController.State.Buffering -> "缓冲中…"
                    is PlayerController.State.Ready -> "就绪"
                    is PlayerController.State.Playing -> "播放中"
                    is PlayerController.State.Paused -> "已暂停"
                    is PlayerController.State.Ended -> "播放结束"
                    else -> "空闲"
                },
                dimens.caption,
                colors.textMuted,
                maxLines = 2,
            )
        }
        }

        if (hudVisible) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvLabel(current.vodName, dimens.cardTitle, colors.text, weight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                    TvLabel("第 $episodeIndex 集 · ${current.playFrom}", dimens.caption, colors.textSecondary, maxLines = 1)
                    Spacer(Modifier.width(1.dp).weight(1f))
                    TvLabel("MStar 硬解", dimens.caption, colors.accent, maxLines = 1)
                }

                Spacer(Modifier.weight(1f))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.68f))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    if (message != null) {
                        TvLabel(message.orEmpty(), dimens.caption, colors.accent, maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                    }
                    ProgressBar(positionMs = position, durationMs = duration, live = false)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvButton("‹ 返回", onClick = onBack)
                        TvButton("⏪ 10s", onClick = {
                            val target = (position - 10_000L).coerceAtLeast(0L)
                            controller.seekTo(target)
                        }, enabled = true)
                        TvButton(
                            text = if (state is PlayerController.State.Playing) "⏸ 暂停" else "▶ 播放",
                            onClick = {
                                if (state is PlayerController.State.Playing) controller.pause() else controller.resume()
                            },
                            enabled = true,
                            primary = true,
                        )
                        TvButton("⏩ 10s", onClick = {
                            val max = duration.coerceAtLeast(0L)
                            val target = (position + 10_000L).coerceAtMost(if (max > 0) max else position + 10_000L)
                            controller.seekTo(target)
                        }, enabled = true)
                        TvButton("倍速 ${formatSpeed(speed)}", onClick = {
                            val next = SPEEDS[(SPEEDS.indexOf(speed).coerceAtLeast(0) + 1) % SPEEDS.size]
                            speed = next
                            controller.setSpeed(next)
                        }, enabled = true)
                        TvButton("画质 ${QUALITIES[qualityIndex].second}", onClick = {
                            qualityIndex = (qualityIndex + 1) % QUALITIES.size
                            controller.setQuality(QUALITIES[qualityIndex].first)
                        }, enabled = true)
                        TvButton("音画 ${audioDelay}ms", onClick = {
                            audioDelay = if (audioDelay >= 500) -500 else audioDelay + 50
                            controller.setAudioDelayMs(audioDelay)
                        }, enabled = true)
                        TvButton("选集", onClick = { showEpisodes = true })
                        TvButton("换源", onClick = { showSources = true })
                    }
                }
            }
        }

        if (showEpisodes) {
            EpisodeSheet(
                episodes = current.episodes,
                selectedIndex = episodeIndex,
                onClose = { showEpisodes = false },
                onPick = { index ->
                    showEpisodes = false
                    TvLog.i("PLAYER_EVT", "action=episode index=$index from=${current.playFrom} source=${current.sourceId}")
                    val next = current.copy(episodeIndex = index)
                    startPlay(next, 0L)
                },
            )
        }

        if (showSources) {
            SourceSheet(
                current = current,
                currentIndex = episodeIndex,
                alternates = alternates,
                availability = altAvailability,
                loading = loadingAlternates,
                switching = switching,
                onClose = { showSources = false },
                onPick = { candidate ->
                    if (switching) return@SourceSheet
                    switching = true
                    scope.launch {
                        when (val result = repo.getDetail(candidate.vodId, candidate.sourceId)) {
                            is Result.Ok -> {
                                val detail = result.value
                                val firstFrom = detail.playFroms.firstOrNull()
                                val episodes = detail.episodesByFrom[firstFrom].orEmpty()
                                if (episodes.none { it.index == episodeIndex }) {
                                    message = "《${candidate.name}》缺第 $episodeIndex 集，已留在原线路"
                                } else {
                                    TvLog.i("PLAYER_EVT", "action=source source=${candidate.sourceId} index=$episodeIndex")
                                    showSources = false
                                    startPlay(
                                        candidate.toRequest(detail, firstFrom.orEmpty(), episodeIndex),
                                        position,
                                    )
                                }
                            }
                            is Result.Err -> message = "换源失败：${result.error.display()}"
                        }
                        switching = false
                    }
                },
            )
        }
    }
}

@Composable
private fun ProgressBar(positionMs: Long, durationMs: Long, live: Boolean) {
    val colors = LocalTvColors.current
    val barHeight = if (LocalTvTier.current.isLarge) 10.dp else 6.dp
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        TvLabel(formatTime(positionMs), LocalTvDimens.current.caption, colors.textSecondary, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(barHeight)
                .background(colors.divider),
        ) {
            if (!live && fraction > 0f) {
                Box(Modifier.fillMaxWidth(fraction).height(barHeight).background(colors.accent))
            }
        }
        Spacer(Modifier.width(10.dp))
        TvLabel(
            if (live) "LIVE" else formatTime(durationMs),
            LocalTvDimens.current.caption,
            colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun EpisodeSheet(
    episodes: List<com.tvbox.app.data.Episode>,
    selectedIndex: Int,
    onClose: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { focus.requestFocus() }
    }
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .padding(28.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvLabel("选集（共 ${episodes.size} 集）", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
                Spacer(Modifier.width(1.dp).weight(1f))
                TvButton("关闭", onClick = onClose)
            }
            Spacer(Modifier.height(12.dp))
            if (episodes.isEmpty()) {
                TvEmptyState("没有分集信息")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(118.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(episodes, key = { it.index }) { episode ->
                        TvButton(
                            text = if (episode.index == selectedIndex) "▶ ${episode.title}" else episode.title,
                            onClick = { onPick(episode.index) },
                            primary = episode.index == selectedIndex,
                            modifier = Modifier
                                .width(118.dp)
                                .then(
                                    if (episode.index == episodes.firstOrNull()?.index) {
                                        Modifier.focusRequester(focus)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceSheet(
    current: PlaybackRequest,
    currentIndex: Int,
    alternates: List<VodSummary>,
    availability: Map<String, Boolean>,
    loading: Boolean,
    switching: Boolean,
    onClose: () -> Unit,
    onPick: (VodSummary) -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(28.dp),
    ) {
        Column(Modifier.fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvLabel("换源 · ${current.vodName}", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
                Spacer(Modifier.width(1.dp).weight(1f))
                TvButton("关闭", onClick = onClose)
            }
            Spacer(Modifier.height(6.dp))
            TvLabel("当前：${current.sourceId} · 第 $currentIndex 集；换源会尽量保持集号", dimens.caption, colors.textMuted)
            Spacer(Modifier.height(12.dp))
            when {
                loading -> TvLoading("正在找同片源…")
                alternates.isEmpty() -> TvEmptyState("没有其它源的同名片")
                else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(alternates, key = { "${it.sourceId}|${it.vodId}" }) { candidate ->
                        val missing = availability[candidate.sourceId] == false
                        Row(
                            Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                TvLabel(candidate.name, dimens.cardTitle, colors.text, maxLines = 1)
                                TvLabel(
                                    if (missing) "源 ${candidate.sourceId} · 缺第 $currentIndex 集"
                                    else "源 ${candidate.sourceId} · ${candidate.year} · ${candidate.remarks}",
                                    dimens.caption,
                                    colors.textMuted,
                                    maxLines = 1,
                                )
                            }
                            TvButton(
                                text = if (missing) "缺第 $currentIndex 集" else if (switching) "切换中…" else "切到该源",
                                enabled = !switching && !missing,
                                primary = true,
                                onClick = { onPick(candidate) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun VodSummary.toRequest(detail: VodDetail, from: String, index: Int): PlaybackRequest =
    PlaybackRequest(
        vodId = detail.vodId,
        sourceId = detail.sourceId,
        vodName = detail.name,
        year = detail.year,
        playFrom = from,
        episodeIndex = index,
        episodes = detail.episodesByFrom[from].orEmpty(),
    )

private fun saveProgress(request: PlaybackRequest, store: WatchProgressStore, positionMs: Long) {
    val episode = request.episodeAt(request.episodeIndex) ?: return
    store.save(
        WatchProgress(
            vodId = request.vodId,
            sourceId = request.sourceId,
            name = request.vodName,
            pic = "",
            playFrom = request.playFrom,
            episodeIndex = request.episodeIndex,
            episodeTitle = episode.title,
            positionMs = positionMs,
            durationMs = 0L,
            updatedAt = System.currentTimeMillis(),
        ),
    )
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"
