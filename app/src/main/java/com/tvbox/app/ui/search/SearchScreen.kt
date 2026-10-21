package com.tvbox.app.ui.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tvbox.app.core.AppError
import com.tvbox.app.core.Result
import com.tvbox.app.data.VodSummary
import com.tvbox.app.search.PinyinIndex
import com.tvbox.app.search.SearchHistory
import com.tvbox.app.search.SearchSeeds
import com.tvbox.app.source.SourceRepository
import com.tvbox.app.ui.LocalTvColors
import com.tvbox.app.ui.LocalTvDimens
import com.tvbox.app.ui.LocalTvMode
import com.tvbox.app.ui.TvMode
import com.tvbox.app.ui.components.LiteListRow
import com.tvbox.app.ui.components.PosterImage
import com.tvbox.app.ui.components.SourceBadge
import com.tvbox.app.ui.components.TvButton
import com.tvbox.app.ui.components.TvChip
import com.tvbox.app.ui.components.TvEmptyState
import com.tvbox.app.ui.components.TvErrorState
import com.tvbox.app.ui.components.TvFocusable
import com.tvbox.app.ui.components.TvLabel
import com.tvbox.app.ui.components.TvLoading
import com.tvbox.app.ui.tvGridColumns
import com.tvbox.app.ui.tvWidthDp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SearchTab(val label: String) {
    ALL("全部"),
    MOVIE("电影"),
    TV("剧集"),
    ANIME("动漫"),
    VARIETY("综艺"),
    OVERSEAS("海外"),
}

private fun tabOf(vod: VodSummary): SearchTab {
    val type = vod.typeName
    val overseasKeys = listOf("韩国", "韩剧", "日本", "日剧", "泰国", "泰剧", "欧美", "美剧", "香港", "港剧", "台湾", "台剧", "海外")
    return when {
        type.contains("动漫") -> SearchTab.ANIME
        type.contains("综艺") -> SearchTab.VARIETY
        overseasKeys.any { type.contains(it) } -> SearchTab.OVERSEAS
        type.contains("剧") -> SearchTab.TV
        type.contains("电影") || type.contains("片") -> SearchTab.MOVIE
        else -> SearchTab.ALL
    }
}

/**
 * 搜索页（docs/SEARCH_SPEC.md）：
 *  - 本地首拼/全拼即时命中（PinyinIndex）；
 *  - 300ms 防抖后走多源聚合 API；
 *  - 分段 Tab + 命中高亮 + 同片合并角标 + 首屏 24 项渐进加载；
 *  - 电视遥控器键盘（不依赖系统输入法），BACK 逐字删除；
 *  - 语音：有系统识别才显示按钮，否则隐藏并提示首拼。
 */
@Composable
fun SearchScreen(
    repo: SourceRepository,
    pinyin: PinyinIndex,
    mode: TvMode,
    onOpenDetail: (VodSummary) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val scope = rememberCoroutineScope()
    val historyStore = remember(context) { SearchHistory(context) }

    var query by remember { mutableStateOf("") }
    var searchToken by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf(historyStore.list()) }
    var localHits by remember { mutableStateOf<List<PinyinIndex.Hit>>(emptyList()) }
    var apiItems by remember { mutableStateOf<List<VodSummary>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var apiError by remember { mutableStateOf<AppError?>(null) }
    var tab by remember { mutableStateOf(SearchTab.ALL) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        pinyin.index(SearchSeeds.summaries())
    }

    LaunchedEffect(query, searchToken) {
        val q = query.trim()
        apiItems = emptyList()
        apiError = null
        tab = SearchTab.ALL
        page = 1
        hasMore = false
        if (q.isEmpty()) {
            localHits = emptyList()
            searching = false
            return@LaunchedEffect
        }
        localHits = pinyin.search(q, 60)
        delay(300)
        searching = true
        when (val result = repo.search(q, 1)) {
            is Result.Ok -> {
                apiItems = result.value
                hasMore = result.value.size >= 15
                pinyin.index(result.value)
            }
            is Result.Err -> {
                apiError = result.error
            }
        }
        searching = false
    }

    fun loadMore() {
        val q = query.trim()
        if (q.isEmpty() || searching || loadingMore || !hasMore) return
        loadingMore = true
        scope.launch {
            when (val result = repo.search(q, page + 1)) {
                is Result.Ok -> {
                    val known = apiItems.mapTo(HashSet()) { "${it.sourceId}|${it.vodId}" }
                    apiItems = apiItems + result.value.filter { "${it.sourceId}|${it.vodId}" !in known }
                    page += 1
                    hasMore = result.value.size >= 15
                    pinyin.index(result.value)
                }
                is Result.Err -> hasMore = false
            }
            loadingMore = false
        }
    }

    val merged = remember(localHits, apiItems) { mergeResults(localHits.map { it.vod }, apiItems) }
    val matchMap = remember(localHits) {
        localHits.associate { "${it.vod.sourceId}|${it.vod.vodId}" to it.matched }
    }
    val counts = remember(merged) {
        SearchTab.entries.associateWith { target ->
            when (target) {
                SearchTab.ALL -> merged.size
                else -> merged.count { tabOf(it) == target }
            }
        }
    }
    val shown = remember(merged, tab) {
        if (tab == SearchTab.ALL) merged else merged.filter { tabOf(it) == tab }
    }
    val gridState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            shown.isNotEmpty() && last >= shown.size - 12
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd) loadMore()
    }

    fun submit() {
        val q = query.trim()
        if (q.isNotEmpty()) {
            historyStore.add(q)
            history = historyStore.list()
            searchToken += 1
        }
    }

    val firstKeyFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstKeyFocus.requestFocus() }
    }

    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出片名")
        }
    }
    val voiceAvailable = remember(context) {
        runCatching { context.packageManager.resolveActivity(voiceIntent, 0) != null }.getOrDefault(false)
    }
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                query = text.trim()
                searchToken += 1
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // 支持 USB/蓝牙硬件键盘与 adb input text（遥控器软键盘仍在下方）
                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                val code = event.key.nativeKeyCode
                when {
                    code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                        query += ('a' + (code - KeyEvent.KEYCODE_A))
                        true
                    }
                    code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        query += ('0' + (code - KeyEvent.KEYCODE_0))
                        true
                    }
                    code == KeyEvent.KEYCODE_DEL -> {
                        query = query.dropLast(1)
                        true
                    }
                    code == KeyEvent.KEYCODE_SPACE -> {
                        query += ' '
                        true
                    }
                    code == KeyEvent.KEYCODE_ENTER -> {
                        submit()
                        true
                    }
                    else -> false
                }
            },
    ) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvButton("‹ 返回", onClick = onBack)
            Spacer(Modifier.width(10.dp))
            TvLabel("搜索", dimens.rowTitle, colors.text, weight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(colors.surface)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                TvLabel(
                    text = if (query.isEmpty()) "输入片名首拼（如 lldq）或中文…" else "$query▌",
                    fontSize = dimens.body,
                    color = if (query.isEmpty()) colors.textMuted else colors.text,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            TvButton("清空", onClick = {
                query = ""
                localHits = emptyList()
                apiItems = emptyList()
                apiError = null
            }, enabled = query.isNotEmpty())
            if (voiceAvailable) {
                Spacer(Modifier.width(8.dp))
                TvButton("语音", onClick = { voiceLauncher.launch(voiceIntent) })
            }
            Spacer(Modifier.width(8.dp))
            TvButton("搜索", onClick = { submit() }, primary = true, enabled = query.isNotBlank())
        }

        if (query.isBlank()) {
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvLabel("热搜", dimens.cardTitle, colors.textSecondary, weight = FontWeight.SemiBold)
                ChipsWrap(SearchSeeds.hotWords) { word ->
                    query = word
                    submit()
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvLabel("搜索历史", dimens.cardTitle, colors.textSecondary, weight = FontWeight.SemiBold)
                    Spacer(Modifier.width(12.dp))
                    if (history.isNotEmpty()) {
                        TvButton("清空历史", onClick = {
                            historyStore.clear()
                            history = emptyList()
                        })
                    }
                }
                if (history.isEmpty()) {
                    TvLabel("暂无历史，试试首拼 lldq → 流浪地球", dimens.caption, colors.textMuted)
                } else {
                    ChipsWrap(history) { word ->
                        query = word
                        submit()
                    }
                }
                if (!voiceAvailable) {
                    TvLabel("本机不支持系统语音识别，可用首拼/全拼搜索", dimens.caption, colors.textMuted)
                }
            }
        } else {
            if (localHits.isNotEmpty() && query.any { it in 'a'..'z' || it in 'A'..'Z' }) {
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvLabel("首拼候选", dimens.caption, colors.textMuted)
                    localHits.take(6).forEach { hit ->
                        TvChip(
                            text = "${hit.vod.name}（${hit.matched}）",
                            selected = false,
                            onClick = {
                                query = hit.vod.name
                                submit()
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchTab.entries.forEach { target ->
                    TvChip(
                        text = "${target.label} ${counts[target] ?: 0}",
                        selected = tab == target,
                        onClick = { tab = target },
                    )
                }
            }
        }

        if (query.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f)) {
            when {
                query.isBlank() -> TvEmptyState("输入首拼或中文开始搜索")
                searching && merged.isEmpty() -> TvLoading("正在聚合搜索…")
                apiError != null && merged.isEmpty() -> TvErrorState(
                    error = apiError ?: AppError("E_UNKNOWN", "搜索失败"),
                    onRetry = { searchToken++ },
                )
                merged.isEmpty() -> TvEmptyState("没有找到「${query.trim()}」，换个词试试")
                else -> {
                    val columns = tvGridColumns()
                    val contentWidth = tvWidthDp() - (if (mode == TvMode.LITE) 300 else 150) - 96 - 18
                    val cardWidth = ((contentWidth - (columns - 1) * 16) / columns).coerceIn(112, 200).dp
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(shown, key = { "${it.sourceId}|${it.vodId}" }) { vod ->
                            if (mode == TvMode.LITE) {
                                LiteListRow(vod = vod, onClick = {
                                    if (vod.sourceId == SearchSeeds.SOURCE_ID) {
                                        query = vod.name
                                        submit()
                                    } else {
                                        onOpenDetail(vod)
                                    }
                                })
                            } else {
                                SearchResultCard(
                                    vod = vod,
                                    query = query.trim(),
                                    matched = matchMap["${vod.sourceId}|${vod.vodId}"],
                                    width = cardWidth,
                                    onClick = {
                                        if (vod.sourceId == SearchSeeds.SOURCE_ID) {
                                            query = vod.name
                                            submit()
                                        } else {
                                            onOpenDetail(vod)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        if (query.isNotBlank()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TvLabel(
                text = "共 ${shown.size} 个结果 · 已加载 ${apiItems.size} · 本地命中 ${localHits.size}",
                fontSize = dimens.caption,
                color = colors.textMuted,
                maxLines = 1,
            )
            Spacer(Modifier.width(1.dp).weight(1f))
            if (loadingMore) {
                TvLabel("加载中…", dimens.caption, colors.accent)
            } else if (hasMore && shown.isNotEmpty()) {
                TvButton("加载更多", onClick = { loadMore() })
            }
        }
        }

        TvKeyboard(
            firstKeyFocus = firstKeyFocus,
            onKey = { ch -> query += ch },
            onBackspace = { query = query.dropLast(1) },
            onClear = { query = "" },
            onSearch = { submit() },
        )
    }
    }
}

@Composable
private fun ChipsWrap(
    words: List<String>,
    onClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { word ->
                    TvChip(text = word, selected = false, onClick = { onClick(word) })
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    vod: VodSummary,
    query: String,
    matched: String?,
    width: Dp,
    onClick: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    TvFocusable(
        modifier = Modifier.width(width),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        onClick = onClick,
    ) { focused ->
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            ) {
                PosterImage(url = vod.pic, modifier = Modifier.fillMaxSize())
                if (vod.sourceCount > 1) {
                    SourceBadge(
                        count = vod.sourceCount,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            HighlightedTitle(
                text = vod.name,
                query = query,
                color = if (focused) colors.accent else colors.text,
                fontSize = dimens.cardTitle,
            )
            TvLabel(
                listOfNotNull(
                    vod.year.takeIf { it.isNotBlank() },
                    vod.typeName.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                dimens.caption,
                colors.textMuted,
                maxLines = 1,
            )
            if (!matched.isNullOrBlank() && matched != query) {
                TvLabel("$matched · 首拼命中", dimens.caption, colors.accent, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HighlightedTitle(
    text: String,
    query: String,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    val colors = LocalTvColors.current
    val annotated: AnnotatedString = remember(text, query) {
        buildAnnotatedString {
            if (query.isBlank()) {
                append(text)
                return@buildAnnotatedString
            }
            val index = text.indexOf(query, ignoreCase = true)
            if (index < 0) {
                append(text)
            } else {
                append(text.substring(0, index))
                withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold)) {
                    append(text.substring(index, index + query.length))
                }
                append(text.substring(index + query.length))
            }
        }
    }
    BasicText(
        text = annotated,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.SansSerif,
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = 2,
    )
}

@Composable
private fun TvKeyboard(
    firstKeyFocus: androidx.compose.ui.focus.FocusRequester,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
) {
    val colors = LocalTvColors.current
    val lite = LocalTvMode.current == TvMode.LITE
    val rows = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
    Column(Modifier.fillMaxWidth().background(if (lite) colors.bg else colors.surface).padding(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { ch ->
                    val keyModifier = if (ch == '1') {
                        Modifier.weight(1f).focusRequester(firstKeyFocus)
                    } else {
                        Modifier.weight(1f)
                    }
                    KeyButton(ch.toString(), keyModifier, onClick = { onKey(ch) })
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            KeyButton("删除", Modifier.weight(1.2f), onClick = onBackspace)
            KeyButton("清空", Modifier.weight(1.2f), onClick = onClear)
            KeyButton("空格", Modifier.weight(3f), onClick = { onKey(' ') })
            KeyButton("搜索", Modifier.weight(1.4f), primary = true, onClick = onSearch)
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    TvButton(text = label, onClick = onClick, modifier = modifier, primary = primary, compact = true)
}

private fun mergeResults(seed: List<VodSummary>, api: List<VodSummary>): List<VodSummary> {
    val result = LinkedHashMap<String, VodSummary>()
    val fingerprint = { vod: VodSummary -> "${normalize(vod.name)}|${vod.year}" }
    for (vod in api) result[fingerprint(vod)] = vod
    val apiNames = api.mapTo(HashSet()) { normalize(it.name) }
    for (vod in seed) {
        if (normalize(vod.name) in apiNames) continue
        val key = fingerprint(vod)
        if (!result.containsKey(key)) result[key] = vod
    }
    return result.values.toList()
}

private fun normalize(name: String): String =
    name.replace(Regex("[\\s\\p{P}\\p{S}]+"), "").lowercase()

/** 保持 Context 引用，避免被 lint 当成未使用（语音 Intent 解析用）。 */
private fun unusedContext(context: Context): Context = context
