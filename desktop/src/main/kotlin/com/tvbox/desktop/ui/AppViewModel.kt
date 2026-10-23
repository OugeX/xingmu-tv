package com.tvbox.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tvbox.desktop.core.Log
import com.tvbox.desktop.data.Aggregator
import com.tvbox.desktop.data.AppPaths
import com.tvbox.desktop.data.AppSettings
import com.tvbox.desktop.data.Episode
import com.tvbox.desktop.data.FavoritesStore
import com.tvbox.desktop.data.FileCache
import com.tvbox.desktop.data.Http
import com.tvbox.desktop.data.ImportedSource
import com.tvbox.desktop.data.LiveCatalog
import com.tvbox.desktop.data.LiveCatalogBuilder
import com.tvbox.desktop.data.LiveCategory
import com.tvbox.desktop.data.LiveChannel
import com.tvbox.desktop.data.LiveFavorite
import com.tvbox.desktop.data.LiveHd
import com.tvbox.desktop.data.LivePaging
import com.tvbox.desktop.data.LiveSeedLoader
import com.tvbox.desktop.data.LiveSnapshot
import com.tvbox.desktop.data.MacCmsClient
import com.tvbox.desktop.data.PlayLine
import com.tvbox.desktop.data.PlaybackRequest
import com.tvbox.desktop.data.RecentLive
import com.tvbox.desktop.data.RecentStore
import com.tvbox.desktop.data.SourceConfig
import com.tvbox.desktop.data.SourceIssue
import com.tvbox.desktop.data.SourceKind
import com.tvbox.desktop.data.SourceSpecParser
import com.tvbox.desktop.data.SourceStore
import com.tvbox.desktop.data.VodDetail
import com.tvbox.desktop.data.VodFavorite
import com.tvbox.desktop.data.VodRef
import com.tvbox.desktop.data.VodSummary
import com.tvbox.desktop.data.WatchProgress
import com.tvbox.desktop.data.WatchProgressStore
import com.tvbox.desktop.player.PlayerController
import com.tvbox.desktop.player.VlcLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

enum class Section(val label: String, val kicker: String, val desc: String) {
    LIVE("直播", "LIVE", "内置频道种子"),
    MOVIE("电影", "MOVIES", "多源聚合"),
    TV("电视剧", "SERIES", "多源聚合"),
    ANIME("动漫", "ANIME", "多源聚合"),
    VARIETY("综艺", "VARIETY", "多源聚合"),
    SEARCH("搜索", "SEARCH", "跨源搜索"),
    SOURCES("源管理", "SOURCES", "tvapp.source.v1.1"),
    SETTINGS("设置", "SETTINGS", "播放与路径");

    val isBrowse: Boolean get() = this in setOf(MOVIE, TV, ANIME, VARIETY)
}

/**
 * 桌面端状态容器（Compose 状态 + 数据编排）。所有状态在 Main 线程更新，IO 用 withContext。
 */
class AppViewModel(
    val paths: AppPaths,
    private val scope: CoroutineScope,
    private val autoplayLive: Boolean = false,
    private val vlcHomeArg: String? = null,
) {
    data class Toast(val id: Long, val message: String, val error: Boolean)

    val http = Http()
    private val cache = FileCache(paths.cacheDir.resolve("http"))
    private val macCms = MacCmsClient(http, cache)
    private val aggregator = Aggregator(macCms)
    private val sourceStore = SourceStore(paths, http)
    private val progressStore = WatchProgressStore(paths.progressFile)
    private val favoritesStore = FavoritesStore(paths.favoritesFile)
    private val recentStore = RecentStore(paths.recentFile)
    val player = PlayerController(scope)

    var settings by mutableStateOf(AppSettings.load(paths))
        private set
    var sources by mutableStateOf<List<SourceConfig>>(emptyList())
        private set
    private var builtinImported: List<ImportedSource> = emptyList()
    private var userImported: List<ImportedSource> = emptyList()

    var builtinIssueCount by mutableStateOf(0)
        private set
    var booting by mutableStateOf(true)
        private set
    var bootMessage by mutableStateOf("正在启动…")
        private set
    var vlcRuntime by mutableStateOf<VlcLocator.Runtime?>(null)
        private set
    var vlcMessage by mutableStateOf("检测 libVLC…")
        private set
    var section by mutableStateOf(Section.LIVE)
        private set
    var toast by mutableStateOf<Toast?>(null)
        private set

    // 直播
    var live by mutableStateOf<LiveSnapshot?>(null)
        private set
    var liveCatalog by mutableStateOf<LiveCatalog?>(null)
        private set
    var liveCategoryId by mutableStateOf("hd")
        private set
    var localPage by mutableStateOf(0)
        private set
    var liveQuery by mutableStateOf("")
    var selectedChannel by mutableStateOf<LiveChannel?>(null)
        private set
    private var hdKeys: Set<String> = emptySet()

    // 点播
    var browseItems by mutableStateOf<List<VodSummary>>(emptyList())
        private set
    var browseLoading by mutableStateOf(false)
        private set
    var browseError by mutableStateOf<String?>(null)
        private set
    var browseNotes by mutableStateOf<List<String>>(emptyList())
        private set
    var browsePage by mutableStateOf(1)
        private set
    var browseHasMore by mutableStateOf(false)
        private set
    var selectedVod by mutableStateOf<VodSummary?>(null)
        private set
    var detail by mutableStateOf<VodDetail?>(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var detailError by mutableStateOf<String?>(null)
        private set
    private var detailJob: Job? = null

    // 搜索
    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<VodSummary>>(emptyList())
        private set
    var searchLoading by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    var searchNotes by mutableStateOf<List<String>>(emptyList())
        private set
    var searchPerformed by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    // 播放
    var playback by mutableStateOf<PlaybackRequest?>(null)
        private set
    var playingEpisodeTitle by mutableStateOf<String?>(null)
        private set

    // 进度 / 收藏 / 最近
    var progressAll by mutableStateOf<List<WatchProgress>>(emptyList())
        private set
    var watchedEpisodes by mutableStateOf<Set<Int>>(emptySet())
        private set
    var vodFavorites by mutableStateOf<List<VodFavorite>>(emptyList())
        private set
    var liveFavorites by mutableStateOf<List<LiveFavorite>>(emptyList())
        private set
    var recentLive by mutableStateOf<List<RecentLive>>(emptyList())
        private set

    val continueWatching: List<WatchProgress>
        get() = progressAll.take(CONTINUE_LIMIT)

    private data class VodContext(
        val sourceId: String,
        val vodId: String,
        val name: String,
        val pic: String,
        val playFrom: String,
        val episodeIndex: Int,
        val episodeTitle: String,
        val nextEpisodeTitle: String?,
        val finished: Boolean = false,
    )

    private var vodContext: VodContext? = null
    private var progressTicker: Job? = null

    // 源导入
    var sourceImportText by mutableStateOf("")
    var sourceImportIssues by mutableStateOf<List<SourceIssue>>(emptyList())
        private set
    var sourceImportMessage by mutableStateOf<String?>(null)
        private set
    var importing by mutableStateOf(false)
        private set
    var sourceProbe by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private var toastSeq = 0L

    val activeSources: List<SourceConfig>
        get() = sources.filter { it.enabled }

    val activeMacCmsSources: List<SourceConfig>
        get() = sources.filter { it.enabled && it.kind == SourceKind.MACCMS }

    val currentCategory: LiveCategory?
        get() = liveCatalog?.category(liveCategoryId)

    private fun filterChannels(channels: List<LiveChannel>): List<LiveChannel> {
        val query = liveQuery.trim().lowercase()
        if (query.isEmpty()) return channels
        return channels.filter { channel ->
            channel.name.lowercase().contains(query) || channel.group.lowercase().contains(query)
        }
    }

    /** 当前分组列表；地方组按每页 60 台分页。 */
    val visibleChannels: List<LiveChannel>
        get() {
            val channels = filterChannels(liveCatalog?.channelsFor(liveCategoryId).orEmpty())
            return if (liveCategoryId == LOCAL_GROUP_ID) LivePaging.page(channels, localPage) else channels
        }

    val localPageCount: Int
        get() {
            val total = filterChannels(liveCatalog?.channelsFor(LOCAL_GROUP_ID).orEmpty()).size
            return LivePaging.pageCount(total)
        }

    val localPageNumber: Int
        get() = (localPage + 1).coerceAtMost(localPageCount)

    val currentCategoryName: String
        get() = currentCategory?.name ?: "直播频道"

    val liveChannelCount: Int
        get() = live?.channels?.size ?: 0

    val statusLine: String
        get() = when {
            booting -> bootMessage
            player.state.active -> player.state.statusText.ifBlank { "播放中" }
            else -> "就绪 · ${activeMacCmsSources.size} 个点播源 · $liveChannelCount 个直播频道"
        }

    init {
        scope.launch { boot() }
    }

    private suspend fun boot() {
        bootMessage = "加载内置源…"
        val builtin = withContext(Dispatchers.IO) { sourceStore.loadBuiltin() }
        builtinImported = builtin.sources
        builtinIssueCount = builtin.issues.size
        if (builtin.error != null) showToast(builtin.error, error = true)

        bootMessage = "加载用户导入源…"
        userImported = withContext(Dispatchers.IO) { sourceStore.loadUser() }
        reloadSources()

        bootMessage = "加载续播/收藏/最近…"
        progressAll = withContext(Dispatchers.IO) { progressStore.list() }
        vodFavorites = withContext(Dispatchers.IO) { favoritesStore.vodList() }
        liveFavorites = withContext(Dispatchers.IO) { favoritesStore.liveList() }
        recentLive = withContext(Dispatchers.IO) { recentStore.list() }
        hdKeys = withContext(Dispatchers.IO) { LiveHd.loadKeys(paths) }

        bootMessage = "加载直播频道…"
        live = withContext(Dispatchers.IO) { LiveSeedLoader.load(paths) }
        liveCategoryId = "hd"
        rebuildLiveCatalog()
        selectedChannel = preferredLiveChannel()

        bootMessage = "检测 libVLC…"
        detectVlcInternal(showResult = false)

        booting = false
        bootMessage = "就绪"
        Log.i("App", "bootstrap done: sources=${sources.size}, live=${live?.channels?.size}")

        if (autoplayLive) {
            delay(700)
            selectedChannel?.let { startLive(it) }
        }
    }

    private suspend fun detectVlcInternal(showResult: Boolean) {
        val requested = vlcHomeArg?.takeIf { it.isNotBlank() } ?: settings.vlcHome.takeIf { it.isNotBlank() }
        val result = withContext(Dispatchers.IO) { VlcLocator.locate(requested) }
        vlcRuntime = result.runtime
        if (result.runtime != null) {
            val error = player.prepare(result.runtime, settings)
            vlcMessage = if (error == null) {
                "libVLC ${player.state.vlcVersion ?: "已加载"}"
            } else {
                error
            }
            if (error == null && showResult) showToast("已连接 ${result.runtime.displayPath}")
            if (error != null) showToast(error, error = true)
        } else {
            vlcMessage = result.message
            if (showResult) showToast("未找到 libVLC：${result.message}", error = true)
        }
    }

    fun detectVlc() {
        scope.launch { detectVlcInternal(showResult = true) }
    }

    fun setVlcHome(path: String) {
        settings = settings.copy(vlcHome = path).also { it.save(paths) }
        scope.launch { detectVlcInternal(showResult = true) }
    }

    fun setMaxQuality(value: String) {
        settings = settings.copy(maxQuality = value).also { it.save(paths) }
    }

    fun setHardwareDecode(enabled: Boolean) {
        settings = settings.copy(hardwareDecode = enabled).also { it.save(paths) }
        vlcRuntime?.let { player.prepare(it, settings) }
        showToast(if (enabled) "已启用硬件解码（失败会自动软解/降清晰度）" else "已强制软件解码")
    }

    fun setVolume(volume: Int) {
        settings = settings.copy(volume = volume.coerceIn(0, 100)).also { it.save(paths) }
        player.setVolume(settings.volume)
    }

    fun clearCache() {
        scope.launch {
            cache.clear()
            showToast("已清空 HTTP 缓存")
        }
    }

    private fun reloadSources() {
        val merged = sourceStore.merge(builtinImported, userImported)
        val disabled = settings.disabledSourceIds
        sources = merged.map { it.copy(enabled = it.enabled && it.id !in disabled) }
    }

    fun openSection(newSection: Section) {
        if (section == newSection) return
        if (player.state.active) stopPlayback()
        section = newSection
        when {
            newSection.isBrowse -> loadBrowse(reset = true)
            newSection == Section.LIVE && live == null -> scope.launch {
                live = withContext(Dispatchers.IO) { LiveSeedLoader.load(paths) }
                hdKeys = withContext(Dispatchers.IO) { LiveHd.loadKeys(paths) }
                liveCategoryId = "hd"
                rebuildLiveCatalog()
                selectedChannel = preferredLiveChannel()
            }
        }
    }

    private fun rebuildLiveCatalog() {
        val snapshot = live ?: return
        val catalog = LiveCatalogBuilder.build(
            snapshot = snapshot,
            hdKeys = hdKeys,
            favoriteIds = liveFavorites.map { it.channelId },
            recentIds = recentLive.map { it.channelId },
        )
        liveCatalog = catalog
        if (catalog.category(liveCategoryId) == null) {
            liveCategoryId = catalog.categories.firstOrNull()?.id ?: "hd"
        }
    }

    /** 自动选中：优先 CCTV-1（实测稳定 1080p 源），否则第一个健康频道。 */
    private fun preferredLiveChannel(): LiveChannel? {
        val snapshot = live ?: return null
        return snapshot.channels.firstOrNull { it.id == "cctv1" }
            ?: snapshot.channels.firstOrNull { it.status == "healthy" && it.lines.isNotEmpty() }
            ?: snapshot.channels.firstOrNull { it.lines.isNotEmpty() }
            ?: snapshot.channels.firstOrNull()
    }

    fun setLiveCategory(categoryId: String) {
        if (liveCategoryId == categoryId) return
        liveCategoryId = categoryId
        localPage = 0
        selectedChannel = visibleChannels.firstOrNull()
    }

    fun nextLocalPage() {
        if (localPage + 1 < localPageCount) {
            localPage += 1
            selectedChannel = visibleChannels.firstOrNull()
        }
    }

    fun previousLocalPage() {
        if (localPage > 0) {
            localPage -= 1
            selectedChannel = visibleChannels.firstOrNull()
        }
    }

    fun selectChannel(channel: LiveChannel) {
        selectedChannel = channel
    }

    fun refresh() {
        when {
            section == Section.LIVE -> scope.launch {
                live = withContext(Dispatchers.IO) { LiveSeedLoader.load(paths) }
                hdKeys = withContext(Dispatchers.IO) { LiveHd.loadKeys(paths) }
                rebuildLiveCatalog()
                selectedChannel = preferredLiveChannel()
                showToast("直播频道已刷新")
            }
            section.isBrowse -> loadBrowse(reset = true)
            section == Section.SEARCH -> if (searchQuery.isNotBlank()) search(searchQuery)
            section == Section.SOURCES -> scope.launch {
                builtinImported = withContext(Dispatchers.IO) { sourceStore.loadBuiltin() }.sources
                userImported = withContext(Dispatchers.IO) { sourceStore.loadUser() }
                reloadSources()
                showToast("源列表已刷新")
            }
            else -> detectVlc()
        }
    }

    fun loadBrowse(reset: Boolean) {
        if (browseLoading) return
        val targetSection = section
        if (!targetSection.isBrowse) return
        if (reset) {
            browsePage = 1
            browseItems = emptyList()
            browseError = null
            browseNotes = emptyList()
            browseHasMore = false
            detail = null
            selectedVod = null
            detailError = null
        }
        browseLoading = true
        val page = browsePage
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                aggregator.browse(sources, targetSection.name, page)
            }
            if (section != targetSection) {
                browseLoading = false
                return@launch
            }
            browseNotes = result.notes.map { "${it.sourceName}：${it.message}" }
            browseItems = if (reset) {
                result.items
            } else {
                (browseItems + result.items).distinctBy { it.sourceId + "|" + it.vodId }
            }
            browseHasMore = result.items.isNotEmpty()
            browseLoading = false
            browseError = when {
                browseItems.isNotEmpty() -> null
                result.notes.isNotEmpty() -> "所有源都失败了：${result.notes.first().message}"
                else -> "当前分类没有内容"
            }
            if (reset && browseItems.isNotEmpty()) selectVod(browseItems.first())
        }
    }

    fun loadMore() {
        if (browseLoading || !browseHasMore) return
        browsePage += 1
        loadBrowse(reset = false)
    }

    fun selectVod(item: VodSummary, ref: VodRef? = null) {
        selectedVod = item
        detailJob?.cancel()
        detail = null
        detailError = null
        detailLoading = true
        val target = ref ?: item.refs.firstOrNull() ?: VodRef(item.sourceId, item.sourceName, item.vodId)
        detailJob = scope.launch {
            val source = sources.firstOrNull { it.id == target.sourceId }
            if (source == null) {
                detailLoading = false
                detailError = "找不到源 ${target.sourceId}"
                return@launch
            }
            val result = withContext(Dispatchers.IO) { macCms.detail(source, target.vodId) }
            if (selectedVod?.vodId != item.vodId) return@launch
            detail = result.value
            detailError = result.error?.message
            detailLoading = false
            watchedEpisodes = result.value?.let {
                withContext(Dispatchers.IO) { progressStore.watchedEpisodes(it.sourceId, it.vodId) }
            }.orEmpty()
        }
    }

    fun search(keyword: String) {
        val query = keyword.trim()
        if (query.isEmpty()) return
        searchQuery = query
        searchPerformed = true
        searchJob?.cancel()
        searchResults = emptyList()
        searchNotes = emptyList()
        searchError = null
        searchLoading = true
        detail = null
        selectedVod = null
        searchJob = scope.launch {
            val result = withContext(Dispatchers.IO) { aggregator.search(sources, query) }
            if (searchQuery != query) return@launch
            searchResults = result.items
            searchNotes = result.notes.map { "${it.sourceName}：${it.message}" }
            searchLoading = false
            searchError = when {
                result.items.isNotEmpty() -> null
                result.notes.isNotEmpty() -> "所有源都失败了：${result.notes.first().message}"
                else -> "没有找到「$query」"
            }
            searchResults.firstOrNull()?.let { selectVod(it) }
        }
    }

    fun playLive(channel: LiveChannel, startLine: Int = 0) {
        startLive(channel, startLine)
    }

    fun startLive(channel: LiveChannel, startLine: Int = 0) {
        if (vlcRuntime == null) {
            showToast("未找到 libVLC。请安装 VLC 3.x，或在「设置」中指定路径。", error = true)
            section = Section.SETTINGS
            return
        }
        if (channel.lines.isEmpty()) {
            showToast("频道「${channel.name}」没有可用线路", error = true)
            return
        }
        finishVodContextForSwitch()
        val rotated = channel.lines.drop(startLine) + channel.lines.take(startLine)
        val lines = rotated.mapIndexed { index, line ->
            PlayLine("线路 ${startLine + index + 1}", line.url, line.quality)
        }
        val request = PlaybackRequest(
            title = channel.name,
            subtitle = "直播 · ${channel.group} · ${channel.qualityLabel}",
            isLive = true,
            lines = lines,
            sourceId = "live",
        )
        recentStore.record(channel.id, channel.name, channel.group)
        recentLive = recentStore.list()
        rebuildLiveCatalog()
        selectedChannel = channel
        playingEpisodeTitle = null
        playback = request
        player.play(request, settings)
    }

    fun playFirstEpisode() {
        val d = detail ?: return
        val first = d.firstEpisode ?: run {
            showToast("《${d.name}》没有可播放地址", error = true)
            return
        }
        startVod(first)
    }

    fun startVod(episode: Episode, fromDetail: VodDetail? = null, startPositionMs: Long = 0L) {
        val d = fromDetail ?: detail ?: return
        if (vlcRuntime == null) {
            showToast("未找到 libVLC。请安装 VLC 3.x，或在「设置」中指定路径。", error = true)
            section = Section.SETTINGS
            return
        }
        finishVodContextForSwitch()
        val source = sources.firstOrNull { it.id == d.sourceId }
        val alternates = d.playFroms
            .filter { it != episode.playFrom }
            .mapNotNull { from ->
                d.episodesByFrom[from]
                    ?.firstOrNull { it.index == episode.index || it.title == episode.title }
                    ?.let { PlayLine(from, it.url) }
            }
        val lines = listOf(PlayLine(episode.playFrom, episode.url)) + alternates
        val request = PlaybackRequest(
            title = "${d.name} · ${episode.title}",
            subtitle = "${d.sourceName} · ${d.typeName.ifBlank { "点播" }}",
            isLive = false,
            lines = lines,
            headers = source?.headers.orEmpty(),
            sourceId = d.sourceId,
            startPositionMs = startPositionMs.coerceAtLeast(0L),
        )
        vodContext = buildVodContext(d, episode)
        playingEpisodeTitle = episode.title
        playback = request
        player.play(request, settings)
        startProgressTicker()
    }

    private fun buildVodContext(detail: VodDetail, episode: Episode): VodContext {
        val episodes = detail.episodesByFrom[episode.playFrom]
            ?: detail.episodesByFrom.values.firstOrNull().orEmpty()
        val position = episodes.indexOfFirst { it.index == episode.index && it.title == episode.title }
            .takeIf { it >= 0 }
            ?: (episode.index - 1).coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
        return VodContext(
            sourceId = detail.sourceId,
            vodId = detail.vodId,
            name = detail.name,
            pic = detail.pic,
            playFrom = episode.playFrom,
            episodeIndex = episode.index,
            episodeTitle = episode.title,
            nextEpisodeTitle = episodes.getOrNull(position + 1)?.title,
        )
    }

    private fun startProgressTicker() {
        progressTicker?.cancel()
        progressTicker = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (vodContext != null && player.state.active && player.state.playing && !player.state.paused) {
                    recordProgress()
                }
            }
        }
    }

    /** 切集/切直播/退出前落盘，并结束当前进度上下文。 */
    private fun finishVodContextForSwitch() {
        recordProgress()
        progressTicker?.cancel()
        progressTicker = null
        vodContext = null
    }

    private fun recordProgress() {
        val context = vodContext ?: return
        if (context.finished) return
        val position = player.state.timeMs
        val duration = player.state.lengthMs
        val outcome = progressStore.save(
            WatchProgress(
                vodId = context.vodId,
                sourceId = context.sourceId,
                name = context.name,
                pic = context.pic,
                playFrom = context.playFrom,
                episodeIndex = context.episodeIndex,
                episodeTitle = context.episodeTitle,
                positionMs = position,
                durationMs = duration,
                updatedAt = System.currentTimeMillis(),
            ),
            nextEpisodeTitle = context.nextEpisodeTitle,
        )
        if (outcome.skipped) return
        progressAll = progressStore.list()
        if (outcome.markedWatched) {
            refreshWatchedForSelection(context.sourceId, context.vodId)
        }
        if (outcome.advancedEpisode) {
            vodContext = context.copy(
                episodeIndex = context.episodeIndex + 1,
                episodeTitle = context.nextEpisodeTitle.orEmpty(),
                nextEpisodeTitle = null,
                finished = true,
            )
        }
    }

    private fun refreshWatchedForSelection(sourceId: String, vodId: String) {
        val selected = selectedVod
        if (selected?.sourceId == sourceId && selected.vodId == vodId) {
            watchedEpisodes = progressStore.watchedEpisodes(sourceId, vodId)
        }
    }

    fun togglePause() {
        if (!player.state.active) return
        val wasPaused = player.state.paused
        player.togglePause()
        if (!wasPaused) recordProgress()
    }

    fun progressFor(sourceId: String, vodId: String): WatchProgress? =
        progressAll.firstOrNull { it.sourceId == sourceId && it.vodId == vodId }

    fun removeProgress(progress: WatchProgress) {
        progressStore.remove(progress.sourceId, progress.vodId)
        progressAll = progressStore.list()
        showToast("已移除续播记录：${progress.name}")
    }

    /** 首页「继续观看」条目：拉详情后按保存的集数与位置起播。 */
    fun continueWatching(progress: WatchProgress) {
        scope.launch {
            val source = sources.firstOrNull { it.id == progress.sourceId }
            if (source == null) {
                showToast("续播失败：源 ${progress.sourceId} 不存在", error = true)
                return@launch
            }
            val result = withContext(Dispatchers.IO) { macCms.detail(source, progress.vodId) }
            val d = result.value
            if (d == null) {
                showToast("续播失败：${result.error?.message ?: "没有详情"}", error = true)
                return@launch
            }
            detail = d
            selectedVod = summaryFromDetail(d)
            watchedEpisodes = withContext(Dispatchers.IO) {
                progressStore.watchedEpisodes(d.sourceId, d.vodId)
            }
            val episodes = d.episodesByFrom[progress.playFrom]
                ?: d.episodesByFrom.values.firstOrNull().orEmpty()
            val episode = episodes.firstOrNull { it.index == progress.episodeIndex }
                ?: episodes.getOrNull(progress.episodeIndex - 1)
                ?: episodes.firstOrNull()
            if (episode == null) {
                showToast("《${d.name}》没有可播放剧集", error = true)
                return@launch
            }
            val seek = if (episode.index == progress.episodeIndex) progress.positionMs else 0L
            startVod(episode, d, seek)
        }
    }

    /** 详情页「继续观看」按钮：不重新拉详情。 */
    fun resumeCurrentDetail() {
        val d = detail ?: return
        val progress = progressStore.lastFor(d.sourceId, d.vodId)
        if (progress == null) {
            playFirstEpisode()
            return
        }
        val episodes = d.episodesByFrom[progress.playFrom]
            ?: d.episodesByFrom.values.firstOrNull().orEmpty()
        val episode = episodes.firstOrNull { it.index == progress.episodeIndex }
            ?: episodes.getOrNull(progress.episodeIndex - 1)
            ?: episodes.firstOrNull()
        if (episode == null) {
            showToast("《${d.name}》没有可播放剧集", error = true)
            return
        }
        val seek = if (episode.index == progress.episodeIndex) progress.positionMs else 0L
        startVod(episode, d, seek)
    }

    private fun summaryFromDetail(d: VodDetail): VodSummary = VodSummary(
        vodId = d.vodId,
        sourceId = d.sourceId,
        sourceName = d.sourceName,
        name = d.name,
        pic = d.pic,
        remarks = d.remarks,
        year = d.year,
        typeId = "",
        typeName = d.typeName,
        score = d.score,
        refs = listOf(VodRef(d.sourceId, d.sourceName, d.vodId)),
    )

    fun toggleVodFavorite(sourceId: String, vodId: String, name: String, pic: String) {
        val favorite = favoritesStore.toggleVod(sourceId, vodId, name, pic)
        vodFavorites = favoritesStore.vodList()
        showToast(if (favorite) "已收藏《$name》" else "已取消收藏《$name》")
    }

    fun toggleLiveFavorite(channel: LiveChannel) {
        val favorite = favoritesStore.toggleLive(channel.id, channel.name, channel.group)
        liveFavorites = favoritesStore.liveList()
        rebuildLiveCatalog()
        showToast(if (favorite) "已收藏频道「${channel.name}」" else "已取消收藏「${channel.name}」")
    }

    fun isVodFavorite(sourceId: String, vodId: String): Boolean =
        vodFavorites.any { it.sourceId == sourceId && it.vodId == vodId }

    fun isLiveFavorite(channelId: String): Boolean =
        liveFavorites.any { it.channelId == channelId }

    fun playEpisodeAt(index: Int) {
        val d = detail ?: return
        val episode = d.episodesByFrom.values.firstOrNull()?.getOrNull(index) ?: return
        startVod(episode, d)
    }

    fun stopPlayback() {
        recordProgress()
        progressTicker?.cancel()
        progressTicker = null
        vodContext = null
        player.stop()
        playback = null
        playingEpisodeTitle = null
    }

    fun toggleSource(id: String, enabled: Boolean) {
        val disabled = settings.disabledSourceIds.toMutableSet()
        if (enabled) disabled.remove(id) else disabled.add(id)
        settings = settings.copy(disabledSourceIds = disabled).also { it.save(paths) }
        reloadSources()
        showToast(if (enabled) "已启用源 $id" else "已停用源 $id")
    }

    fun isUserSource(id: String): Boolean = userImported.any { it.config.id == id }

    fun removeUserSource(id: String) {
        if (!isUserSource(id)) {
            showToast("内置源不能删除，可以停用", error = true)
            return
        }
        userImported = userImported.filterNot { it.config.id == id }
        scope.launch {
            withContext(Dispatchers.IO) { sourceStore.saveUser(userImported) }
            reloadSources()
            showToast("已删除源 $id")
        }
    }

    fun probeSource(source: SourceConfig) {
        sourceProbe = sourceProbe + (source.id to "测试中…")
        scope.launch {
            val result = withContext(Dispatchers.IO) { macCms.probe(source) }
            sourceProbe = sourceProbe + (
                source.id to if (result.ok) "连通 ${result.value} ms" else "失败：${result.error?.message}"
                )
        }
    }

    fun importFromFile(file: Path) {
        scope.launch {
            importing = true
            sourceImportMessage = null
            sourceImportIssues = emptyList()
            val text = runCatching { withContext(Dispatchers.IO) { Files.readString(file) } }.getOrNull()
            if (text == null) {
                importing = false
                sourceImportIssues = listOf(SourceIssue("E_JSON", file.toString(), "无法读取文件"))
                sourceImportMessage = "读取失败：$file"
                return@launch
            }
            applyImport(text, file.toString())
        }
    }

    fun importFromUrl(url: String) {
        if (url.isBlank()) {
            sourceImportMessage = "请输入 URL"
            return
        }
        scope.launch {
            importing = true
            sourceImportMessage = null
            sourceImportIssues = emptyList()
            val text = try {
                withContext(Dispatchers.IO) { sourceStore.fetchUrl(url.trim()) }
            } catch (t: Throwable) {
                importing = false
                sourceImportIssues = listOf(SourceIssue("E_NETWORK", url, t.message ?: "网络失败"))
                sourceImportMessage = "下载失败：${t.message}"
                return@launch
            }
            applyImport(text, url)
        }
    }

    fun importFromText() {
        val text = sourceImportText
        if (text.isBlank()) {
            sourceImportMessage = "请输入要导入的 JSON"
            return
        }
        scope.launch {
            importing = true
            sourceImportMessage = null
            sourceImportIssues = emptyList()
            applyImport(text, "粘贴内容")
        }
    }

    private suspend fun applyImport(text: String, origin: String) {
        val report = withContext(Dispatchers.IO) { SourceSpecParser.parseText(text, origin) }
        importing = false
        if (!report.ok) {
            sourceImportIssues = report.issues
            sourceImportMessage = "校验失败：${report.issues.size} 个问题（错误码与 tools/validate_sources.py 一致）"
            return
        }
        val incoming = report.sources
        userImported = userImported.filter { existing ->
            incoming.none { it.config.id == existing.config.id }
        } + incoming
        withContext(Dispatchers.IO) { sourceStore.saveUser(userImported) }
        reloadSources()
        sourceImportIssues = emptyList()
        sourceImportMessage = "已导入 ${incoming.size} 个源：${incoming.joinToString("、") { it.config.name }}"
        showToast("源导入成功：${incoming.size} 个")
    }

    fun clearImportState() {
        sourceImportIssues = emptyList()
        sourceImportMessage = null
        sourceImportText = ""
    }

    fun showToast(message: String, error: Boolean = false) {
        toast = Toast(++toastSeq, message, error)
    }

    fun dismissToast(id: Long) {
        if (toast?.id == id) toast = null
    }

    fun openExternal(url: String) {
        runCatching {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            }
        }.onFailure { showToast("无法打开浏览器：${it.message}", error = true) }
    }

    fun shutdown() {
        recordProgress()
        progressTicker?.cancel()
        vodContext = null
        player.release()
    }

    private companion object {
        const val CONTINUE_LIMIT = 8
        const val PROGRESS_INTERVAL_MS = 15_000L
        const val LOCAL_GROUP_ID = "local"
    }
}
