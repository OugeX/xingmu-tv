package com.tvbox.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.app.R
import com.tvbox.app.data.AppServices
import com.tvbox.app.data.CanonicalCategory
import com.tvbox.app.data.FallbackSourceRepository
import com.tvbox.app.data.LocalSourceRepository
import com.tvbox.app.data.WatchProgressStore
import com.tvbox.app.search.PinyinIndex
import com.tvbox.app.source.SourceGraph
import com.tvbox.app.ui.category.CategoryScreen
import com.tvbox.app.ui.components.TvButton
import com.tvbox.app.ui.components.TvFocusable
import com.tvbox.app.ui.components.TvLabel
import com.tvbox.app.ui.detail.DetailScreen
import com.tvbox.app.ui.home.HomeScreen
import com.tvbox.app.ui.live.LiveScreen
import com.tvbox.app.ui.player.PlaybackRequest
import com.tvbox.app.ui.player.PlayerScreen
import com.tvbox.app.ui.search.SearchScreen
import com.tvbox.app.ui.source.SourceManageScreen

/** 页面路由（不用 navigation-compose：单一 Activity + 手写回退栈，见 PLAN_ARCHITECTURE §3.2）。 */
sealed interface Dest {
    data object Home : Dest
    data class Category(val typeId: Int, val title: String) : Dest
    data class Detail(val vodId: String, val sourceId: String, val title: String) : Dest
    data object Search : Dest
    data class Play(val request: PlaybackRequest) : Dest
    data object Sources : Dest
    data object Live : Dest
}

private data class NavItem(
    val label: String,
    val glyph: String,
    val dest: Dest,
    val matches: (Dest) -> Boolean,
)

@Composable
fun TvBoxApp(initialMode: TvMode = TvMode.CINEMA, initialDest: Dest? = null) {
    val context = LocalContext.current
    val repo = remember(context) {
        // 接线：t10 的完整源仓库（SQLite + 导入）为主；网络超时时按调用回退到内置实现，
        // 保证低端/弱网真机上首页与详情始终有数据（见 FallbackSourceRepository 注释）。
        val primary = runCatching { SourceGraph.install(context) }.getOrNull()
        val fallback = LocalSourceRepository(context)
        if (primary != null) FallbackSourceRepository(primary, fallback) else fallback
    }
    val pinyin = remember(context) { PinyinIndex(context) }
    val progressStore = remember(context) { WatchProgressStore(context) }
    val displayPrefs = remember(context) { DisplayPrefs(context) }

    val stack = remember { mutableStateListOf<Dest>(initialDest ?: Dest.Home) }
    var mode by remember { mutableStateOf(initialMode) }
    // 冷启动首帧就用持久化档位，避免先小后大闪一下
    var tier by remember { mutableStateOf(displayPrefs.readTier()) }
    val forcedLite = shouldForceLiteMode()
    val effectiveMode = if (forcedLite) TvMode.LITE else mode

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

    BackHandler(enabled = stack.size > 1) { pop() }

    TvTheme(mode = effectiveMode) {
        CompositionLocalProvider(LocalTvTier provides tier) {
        CompositionLocalProvider(LocalTvDimens provides tvDimens()) {
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
                        .padding(horizontal = OverscanHorizontal, vertical = OverscanVertical),
                ) {
                    NavRail(
                        current = current,
                        mode = effectiveMode,
                        forcedLite = forcedLite,
                        onNavigate = ::navigateRoot,
                        onToggleMode = {
                            mode = if (mode == TvMode.CINEMA) TvMode.LITE else TvMode.CINEMA
                        },
                        onCycleTier = {
                            val next = when (tier) {
                                FontTier.STANDARD -> FontTier.LARGE
                                FontTier.LARGE -> FontTier.XLARGE
                                FontTier.XLARGE -> FontTier.STANDARD
                            }
                            tier = next
                            displayPrefs.writeTier(next)
                        },
                    )
                    Spacer(Modifier.width(18.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (current) {
                            is Dest.Home -> HomeScreen(
                                repo = repo,
                                pinyin = pinyin,
                                progressStore = progressStore,
                                mode = effectiveMode,
                                onOpenDetail = { vod -> push(Dest.Detail(vod.vodId, vod.sourceId, vod.name)) },
                                onOpenCategory = { typeId, title -> push(Dest.Category(typeId, title)) },
                            )

                            is Dest.Category -> CategoryScreen(
                                typeId = current.typeId,
                                title = current.title,
                                repo = repo,
                                pinyin = pinyin,
                                mode = effectiveMode,
                                onOpenDetail = { vod -> push(Dest.Detail(vod.vodId, vod.sourceId, vod.name)) },
                                onBack = { pop() },
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
                            )

                            is Dest.Search -> SearchScreen(
                                repo = repo,
                                pinyin = pinyin,
                                mode = effectiveMode,
                                onOpenDetail = { vod -> push(Dest.Detail(vod.vodId, vod.sourceId, vod.name)) },
                                onBack = { pop() },
                            )

                            is Dest.Sources -> SourceManageScreen(repo = repo, onBack = { pop() })

                            is Dest.Play, is Dest.Live -> Unit
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
    val width = when {
        lite -> 300.dp
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
            NavItem("播放源", "源", Dest.Sources) { it is Dest.Sources },
            NavItem("搜索", "搜", Dest.Search) { it is Dest.Search },
        )
    }

    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(colors.surface.copy(alpha = if (lite) 0f else 0.4f))
            .padding(vertical = 10.dp),
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
                fontSize = if (lite) 24.sp else dimens.rowTitle,
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
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (lite) {
                        Box(
                            Modifier
                                .width(4.dp)
                                .height(26.dp)
                                .background(if (selected) colors.accent else Color.Transparent),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Box(
                        Modifier.size(if (tier.isLarge) 44.dp else 30.dp),
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
                    TvLabel(
                        text = item.label,
                        fontSize = if (lite) dimens.body else dimens.cardTitle,
                        color = if (selected) colors.text else colors.textSecondary,
                        weight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
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
