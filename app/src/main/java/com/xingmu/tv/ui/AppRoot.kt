package com.xingmu.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp
import com.xingmu.tv.R
import com.xingmu.tv.data.AppServices
import com.xingmu.tv.data.CanonicalCategory
import com.xingmu.tv.data.FallbackSourceRepository
import com.xingmu.tv.data.LocalSourceRepository
import com.xingmu.tv.data.WatchProgressStore
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.player.PlayerControllerImpl
import com.xingmu.tv.ui.settings.AppSettingsValues
import com.xingmu.tv.ui.settings.SettingsScreen
import com.xingmu.tv.ui.settings.SettingsStore
import com.xingmu.tv.ui.settings.overscanDp
import com.xingmu.tv.data.casual.CasualGraph
import com.xingmu.tv.data.casual.CasualRepository
import com.xingmu.tv.data.casual.ShortClip
import com.xingmu.tv.search.PinyinIndex
import com.xingmu.tv.source.SourceGraph
import com.xingmu.tv.ui.casual.CasualScreen
import com.xingmu.tv.ui.casual.CasualVideoScreen
import com.xingmu.tv.ui.category.CategoryScreen
import com.xingmu.tv.ui.components.TvButton
import com.xingmu.tv.ui.components.TvFocusable
import com.xingmu.tv.ui.components.TvLabel
import com.xingmu.tv.ui.detail.DetailScreen
import com.xingmu.tv.ui.home.HomeScreen
import com.xingmu.tv.ui.live.LiveScreen
import com.xingmu.tv.ui.player.PlaybackRequest
import com.xingmu.tv.ui.player.PlayerScreen
import com.xingmu.tv.ui.search.SearchScreen
import com.xingmu.tv.ui.source.SourceManageScreen

/** 页面路由（不用 navigation-compose：单一 Activity + 手写回退栈，见 PLAN_ARCHITECTURE §3.2）。 */
sealed interface Dest {
    data object Home : Dest
    data class Category(val typeId: Int, val title: String) : Dest
    data class Detail(
        val vodId: String,
        val sourceId: String,
        val title: String,
        /** t29：来源分类 id（分类页进入时用于同分类推荐） */
        val fromTypeId: Int? = null,
    ) : Dest
    data object Search : Dest
    data class Play(val request: PlaybackRequest) : Dest
    data object Sources : Dest
    data object Live : Dest
    data object Settings : Dest
    data object Casual : Dest
    data class CasualVideo(val clips: List<ShortClip>, val startIndex: Int) : Dest
}

private data class NavItem(
    val label: String,
    val glyph: String,
    val dest: Dest,
    val matches: (Dest) -> Boolean,
)

@Composable
fun TvBoxApp(initialMode: TvMode = TvMode.CINEMA, initialDest: Dest? = null, casualSegment: Int = 0, initialSearchQuery: String = "") {
    val context = LocalContext.current
    val repo = remember(context) {
        // 接线：t10 的完整源仓库（SQLite + 导入）为主；网络超时时按调用回退到内置实现，
        // 保证低端/弱网真机上首页与详情始终有数据（见 FallbackSourceRepository 注释）。
        val primary = runCatching { SourceGraph.install(context) }.getOrNull()
        val fallback = LocalSourceRepository(context)
        if (primary != null) FallbackSourceRepository(primary, fallback) else fallback
    }
    val pinyin = remember(context) { PinyinIndex(context) }
    val casualRepo: CasualRepository = remember(context) { CasualGraph.repository(context) }
    val progressStore = remember(context) { WatchProgressStore(context) }
    val settingsStore = remember(context) { SettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.read()) }

    val stack = remember { mutableStateListOf<Dest>(initialDest ?: Dest.Home) }
    // t29：分类页滚动位置按 typeId 缓存，从详情返回时不跳回顶部
    val categoryGridStates = remember {
        mutableMapOf<Int, androidx.compose.foundation.lazy.grid.LazyGridState>()
    }
    LaunchedEffect(Unit) { TvLog.i("AppRoot", "initialDest=$initialDest stack=${stack.joinToString(",")}") }
    var mode by remember { mutableStateOf(if (settings.forceLite) TvMode.LITE else initialMode) }
    val forcedLite = shouldForceLiteMode()
    val effectiveMode = if (settings.forceLite || forcedLite) TvMode.LITE else mode
    val configuration = LocalConfiguration.current
    val (overscanH, overscanV) = overscanDp(
        settings.overscanPercent,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    )

    LaunchedEffect(pinyin) {
        kotlinx.coroutines.delay(2_000)
        runCatching { pinyin.benchmark() }
    }

    // 播放类设置改完立即下发常驻播放器（下一次起播也生效）
    LaunchedEffect(settings.qualityCap, settings.audioDelayMs) {
        runCatching {
            (AppServices.playerOrNull() as? PlayerControllerImpl)?.let { controller ->
                if (settings.qualityCap != 0) controller.setQuality(settings.qualityCap)
                controller.setAudioDelayMs(settings.audioDelayMs)
            }
        }
    }

    fun push(dest: Dest) {
        stack.add(dest)
    }

    fun navigateRoot(dest: Dest) {
        stack.clear()
        stack.add(dest)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    // 离开播放类页面时强制解除「图片暂停」状态，避免 enter/exit 不配对导致封面永久不加载
    androidx.compose.runtime.LaunchedEffect(stack.last()) {
        val cur = stack.last()
        val playback = cur is Dest.Play || cur is Dest.Live || cur is Dest.CasualVideo
        if (!playback) com.xingmu.tv.player.PlaybackMemoryGuard.ensureIdle("nav:${'$'}{cur::class.simpleName}")
    }

    // BACK 语义：有子页就返回上一页；在非首页的一级页面（直播/随便/源管理/设置/搜索）返回首页；
    // 只有在首页时才交回系统（退出应用）。避免「在随便里按 BACK 直接退出 App」。
    BackHandler(enabled = stack.size > 1 || stack.last() != Dest.Home) {
        if (stack.size > 1) {
            pop()
        } else {
            stack.clear()
            stack.add(Dest.Home)
        }
    }

    TvTheme(mode = effectiveMode) {
        CompositionLocalProvider(LocalTvTier provides settings.fontTier) {
        CompositionLocalProvider(LocalTvDimens provides tvDimens()) {
        CompositionLocalProvider(LocalOverscan provides Overscan(overscanH.dp, overscanV.dp)) {
            when (val current = stack.last()) {
                // 播放/直播：沉浸式全屏，左侧导航整体隐藏（UI_SPEC §3.5/§3.6 硬要求）
                is Dest.Play -> {
                    Box(Modifier.fillMaxSize()) {
                        PlayerScreen(
                            request = current.request,
                            repo = repo,
                            progressStore = progressStore,
                            onBack = { pop() },
                        )
                    }
                }

                is Dest.CasualVideo -> {
                    Box(Modifier.fillMaxSize()) {
                        CasualVideoScreen(
                            clips = current.clips,
                            startIndex = current.startIndex,
                            repo = casualRepo,
                            onBack = { pop() },
                        )
                    }
                }

                is Dest.Live -> {
                    Box(Modifier.fillMaxSize()) {
                        LiveScreen(
                            repo = repo,
                            pinyin = pinyin,
                            onBack = { pop() },
                        )
                    }
                }

                else -> Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = LocalOverscan.current.horizontal,
                            vertical = LocalOverscan.current.vertical,
                        ),
                ) {
                    NavRail(
                        current = current,
                        mode = effectiveMode,
                        forcedLite = forcedLite,
                        onNavigate = ::navigateRoot,
                        onToggleMode = {
                            val next = if (effectiveMode == TvMode.CINEMA) TvMode.LITE else TvMode.CINEMA
                            mode = next
                            settings = settings.copy(forceLite = next == TvMode.LITE)
                            settingsStore.write(settings)
                        },
                        onCycleTier = {
                            val next = when (settings.fontTier) {
                                FontTier.STANDARD -> FontTier.LARGE
                                FontTier.LARGE -> FontTier.XLARGE
                                FontTier.XLARGE -> FontTier.STANDARD
                            }
                            settings = settings.copy(fontTier = next)
                            settingsStore.write(settings)
                        },
                    )
                    Spacer(Modifier.width(18.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        // R3：全局底部焦点哨兵，避免连续 DOWN 把焦点推出应用
                        when (current) {
                            is Dest.Home -> HomeScreen(
                                repo = repo,
                                pinyin = pinyin,
                                progressStore = progressStore,
                                mode = effectiveMode,
                                onOpenDetail = { vod -> push(Dest.Detail(vod.vodId, vod.sourceId, vod.name)) },
                                onOpenCategory = { typeId, title -> push(Dest.Category(typeId, title)) },
                                onOpenLive = { push(Dest.Live) },
                            )

                            is Dest.Category -> CategoryScreen(
                                typeId = current.typeId,
                                title = current.title,
                                repo = repo,
                                pinyin = pinyin,
                                mode = effectiveMode,
                                onOpenDetail = { vod -> push(Dest.Detail(vod.vodId, vod.sourceId, vod.name, fromTypeId = current.typeId)) },
                                onBack = { pop() },
                                gridState = categoryGridStates.getOrPut(current.typeId) {
                                    androidx.compose.foundation.lazy.grid.LazyGridState()
                                },
                            )

                            is Dest.Detail -> DetailScreen(
                                vodId = current.vodId,
                                sourceId = current.sourceId,
                                fallbackTitle = current.title,
                                repo = repo,
                                pinyin = pinyin,
                                progressStore = progressStore,
                                mode = effectiveMode,
                                onPlay = { request -> push(Dest.Play(request)) },
                                onBack = { pop() },
                                categoryTypeId = current.fromTypeId,
                            )

                            is Dest.Search -> SearchScreen(
                                repo = repo,
                                pinyin = pinyin,
                                mode = effectiveMode,
                                onOpenDetail = { vod -> push(Dest.Detail(vod.vodId, vod.sourceId, vod.name)) },
                                onBack = { pop() },
                                initialQuery = initialSearchQuery,
                            )

                            is Dest.Sources -> SourceManageScreen(repo = repo, onBack = { pop() })

                            is Dest.Settings -> SettingsScreen(
                                values = settings,
                                onChange = { updated ->
                                    settings = updated
                                    settingsStore.write(updated)
                                },
                                repo = repo,
                                casualRepo = casualRepo,
                                onBack = { pop() },
                            )

                            is Dest.Casual -> CasualScreen(
                                repo = casualRepo,
                                onPlayVideo = { clips, index -> push(Dest.CasualVideo(clips, index)) },
                                initialSegment = casualSegment,
                            )

                            is Dest.Play, is Dest.Live, is Dest.CasualVideo, is Dest.Settings -> Unit
                        }
                        com.xingmu.tv.ui.DpadDownSink(Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        }
        }
    }
}

@Composable
private fun NavRail(
    current: Dest,
    mode: TvMode,
    forcedLite: Boolean,
    onNavigate: (Dest) -> Unit,
    onToggleMode: () -> Unit,
    onCycleTier: () -> Unit,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val tier = LocalTvTier.current
    val lite = mode == TvMode.LITE
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val metrics = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
    val screenWidthPx = metrics.widthPixels
    // R2：≤960 物理宽（含 960×540）的 C 侧栏固定 180dp，字号下调一档，避免裁剪
    val tinyC = lite && (screenWidthPx <= 960 || metrics.heightPixels <= 540)
    val width = when {
        tinyC -> 180.dp
        lite -> (screenWidthDp * 0.42f).coerceIn(220f, 300f).dp
        tier == FontTier.LARGE -> 190.dp
        tier == FontTier.XLARGE -> 220.dp
        else -> 150.dp
    }

    val items = remember {
        listOf(
            NavItem("推荐", "荐", Dest.Home) { it is Dest.Home },
            NavItem("电影", "影", Dest.Category(CanonicalCategory.MOVIE, "电影")) {
                it is Dest.Category && it.typeId == CanonicalCategory.MOVIE
            },
            NavItem("剧集", "剧", Dest.Category(CanonicalCategory.TV, "剧集")) {
                it is Dest.Category && it.typeId == CanonicalCategory.TV
            },
            NavItem("动漫", "漫", Dest.Category(CanonicalCategory.ANIME, "动漫")) {
                it is Dest.Category && it.typeId == CanonicalCategory.ANIME
            },
            NavItem("综艺", "综", Dest.Category(CanonicalCategory.VARIETY, "综艺")) {
                it is Dest.Category && it.typeId == CanonicalCategory.VARIETY
            },
            NavItem("海外", "海", Dest.Category(CanonicalCategory.OVERSEAS_ROOT, "海外")) {
                it is Dest.Category && it.typeId == CanonicalCategory.OVERSEAS_ROOT
            },
            NavItem("直播", "直", Dest.Live) { it is Dest.Live },
            NavItem("随便", "随", Dest.Casual) { it is Dest.Casual || it is Dest.CasualVideo },
            NavItem("播放源", "源", Dest.Sources) { it is Dest.Sources },
            NavItem("设置", "设", Dest.Settings) { it is Dest.Settings },
            NavItem("搜索", "搜", Dest.Search) { it is Dest.Search },
        )
    }

    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(colors.surface.copy(alpha = if (lite) 0f else 0.4f))
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(vertical = if (lite) 4.dp else 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(
                    when {
                        tier == FontTier.XLARGE -> 64.dp
                        tier == FontTier.LARGE -> 56.dp
                        lite -> 38.dp
                        else -> 44.dp
                    },
                ),
            )
            Spacer(Modifier.width(8.dp))
            TvLabel(
                text = stringResource(R.string.app_name),
                fontSize = if (tinyC) 16.sp else if (lite) 24.sp else dimens.rowTitle,
                color = colors.accent,
                weight = FontWeight.Black,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(10.dp))
        items.forEach { item ->
            val selected = item.matches(current)
            TvFocusable(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                shape = RectangleShape,
                onClick = { onNavigate(item.dest) },
            ) { focused ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected || focused) colors.surfaceHigh else Color.Transparent)
                        .padding(
                            horizontal = if (tinyC) 8.dp else if (lite) 6.dp else 10.dp,
                            vertical = if (tinyC) 2.dp else if (lite) 3.dp else 8.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (lite) {
                        Box(
                            Modifier
                                .width(if (tinyC) 3.dp else 4.dp)
                                .height(if (tinyC) 18.dp else 26.dp)
                                .background(if (selected) colors.accent else Color.Transparent),
                        )
                        Spacer(Modifier.width(if (tinyC) 4.dp else 10.dp))
                    }
                    if (!tinyC) {
                        Box(
                            Modifier.size(if (tier.isLarge) 44.dp else if (lite) 24.dp else 30.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TvLabel(
                                text = item.glyph,
                                fontSize = if (lite) 20.sp else 22.sp,
                                color = if (selected) colors.accent else colors.textSecondary,
                                weight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    TvLabel(
                        text = item.label,
                        fontSize = if (tinyC) 14.sp else if (lite) dimens.body else dimens.cardTitle,
                        color = if (selected) colors.text else colors.textSecondary,
                        weight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (!tinyC) {
        TvButton(
            text = if (mode == TvMode.CINEMA) "模式 A · 影院" else "模式 C · 保命",
            onClick = onToggleMode,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        TvButton(
            text = "字号 · ${tier.label}",
            onClick = onCycleTier,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (forcedLite) {
            TvLabel(
                "本机强制 C 模式",
                fontSize = dimens.caption,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        TvLabel(
            "遥控器 BACK 逐级返回",
            fontSize = dimens.caption,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            maxLines = 1,
        )
        }
    }
}
