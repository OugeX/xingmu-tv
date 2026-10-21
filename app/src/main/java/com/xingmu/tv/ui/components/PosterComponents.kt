package com.xingmu.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.data.AppImages
import com.xingmu.tv.data.PosterPolicy
import com.xingmu.tv.data.VodSummary
import com.xingmu.tv.ui.ContentGate
import com.xingmu.tv.ui.LocalTvColors
import com.xingmu.tv.ui.LocalTvDimens
import com.xingmu.tv.ui.LocalTvMode
import com.xingmu.tv.ui.LocalTvTier
import com.xingmu.tv.ui.TvMode

@Composable
fun PosterImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    requestWidth: Int = 320,
    requestHeight: Int = 450,
    fallbackText: String = "",
    /** t28：vod_pic_thumb；有值则首帧直接用它，没值用 [url] 半尺寸先出低清 */
    thumbUrl: String = "",
) {
    val context = LocalContext.current
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val lite = LocalTvMode.current == TvMode.LITE
    val imageLoader = remember(context) { AppImages.loader(context) }
    val scrolling = LocalListScrolling.current

    // t28 渐进加载：先低清（thumb / 半尺寸、INEXACT），列表停稳 250ms 后升级原尺寸（EXACT）。
    // 升级后不回退：新卡片在滚动中仍从低清开始，但已经看清的图不会因为再滚动又变糊。
    val plan = remember(url, thumbUrl, requestWidth, requestHeight) {
        PosterPolicy.plan(url, thumbUrl, requestWidth, requestHeight)
    }
    var upgraded by remember(plan) { mutableStateOf(!plan.canUpgrade) }
    LaunchedEffect(scrolling, plan) {
        if (plan.canUpgrade && !upgraded && !scrolling) {
            kotlinx.coroutines.delay(HI_RES_DELAY_MS)
            upgraded = true
        }
    }

    // 失败自动重试：先原地址，再 http/https 互换一次，仍失败则显示占位图（不再留黑块）
    var attempt by remember(url) { mutableIntStateOf(0) }
    var failed by remember(url) { mutableStateOf(!plan.hasImage) }
    val effectiveUrl = remember(plan, attempt, upgraded) {
        val base = if (upgraded) plan.fullUrl else plan.initialUrl
        when (attempt) {
            0 -> base
            1 -> if (base.startsWith("https://")) base.replaceFirst("https://", "http://")
            else base.replaceFirst("http://", "https://")
            else -> base
        }
    }
    val request = remember(effectiveUrl, lite, plan, upgraded, scrolling) {
        val width = if (upgraded) plan.fullWidth else plan.initialWidth
        val height = if (upgraded) plan.fullHeight else plan.initialHeight
        ImageRequest.Builder(context)
            .data(effectiveUrl)
            .size(width, height)
            .precision(if (PosterPolicy.precisionFor(scrolling) && upgraded) Precision.EXACT else Precision.INEXACT)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .allowRgb565(true)
            .allowHardware(!lite)
            .crossfade(false)
            .build()
    }

    Box(modifier.background(colors.surface)) {
        if (!failed && effectiveUrl.isNotBlank() && !ContentGate.zapping && !com.xingmu.tv.player.PlaybackMemoryGuard.pauseImages) {
            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = { state ->
                    TvLog.w("Coil", "图片加载失败 attempt=$attempt url=$effectiveUrl err=${state.result.throwable.message}")
                    if (attempt < 2) attempt += 1 else failed = true
                },
            )
        }
        if (failed) {
            Box(
                modifier = Modifier.fillMaxSize().background(colors.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TvLabel(
                        text = fallbackText.take(1).ifBlank { "影" },
                        fontSize = dimens.rowTitle,
                        color = colors.textMuted,
                        weight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    TvLabel("暂无封面", dimens.caption, colors.textMuted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun SourceBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 1) return
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    Box(
        modifier = modifier
            .background(colors.accent, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        TvLabel("$count 源", dimens.caption, colors.accentOn, maxLines = 1, weight = FontWeight.Bold)
    }
}

@Composable
fun PosterCard(
    vod: VodSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val lite = LocalTvMode.current == TvMode.LITE
    val largeText = LocalTvTier.current.isLarge
    val shape = if (lite) RectangleShape else RoundedCornerShape(8.dp)

    TvFocusable(
        modifier = modifier.width(width),
        shape = shape,
        borderWidth = 3.dp,
        onClick = onClick,
    ) { focused ->
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(shape),
            ) {
                PosterImage(
                    url = vod.pic,
                    thumbUrl = vod.picThumb,
                    modifier = Modifier.fillMaxSize(),
                    fallbackText = vod.name,
                )
                if (!lite) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(
                                        androidx.compose.ui.graphics.Color.Transparent,
                                        androidx.compose.ui.graphics.Color.Transparent,
                                        colors.bg.copy(alpha = 0.55f),
                                    ),
                                ),
                            ),
                    )
                }
                if (vod.sourceCount > 1) {
                    SourceBadge(
                        count = vod.sourceCount,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
                if (vod.remarks.isNotBlank()) {
                    TvLabel(
                        text = vod.remarks,
                        fontSize = dimens.caption,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(5.dp),
                        maxLines = 1,
                    )
                }
                // t29：评分角标（海报右下，金色描边；有评分才显示）
                val scoreText = vod.score.takeIf { it.isNotBlank() && it != "0.0" }
                if (scoreText != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .border(1.dp, colors.accent, RoundedCornerShape(3.dp))
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        TvLabel(scoreText, dimens.caption, colors.accent, weight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TvLabel(
                text = vod.name,
                fontSize = dimens.cardTitle,
                color = if (focused) colors.accent else colors.text,
                weight = FontWeight.Medium,
                maxLines = if (largeText) 2 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
            // t29：副标题 = 年份 · 子分类 · N 源（UI_TUNE §1.2）
            val subtitle = remember(vod) {
                buildList {
                    vod.year.takeIf { it.isNotBlank() }?.let(::add)
                    vod.typeName.takeIf { it.isNotBlank() }?.let(::add)
                    if (vod.sourceCount > 1) add("${vod.sourceCount} 源")
                }.joinToString(" · ")
            }
            if (subtitle.isNotEmpty()) {
                TvLabel(
                    text = subtitle,
                    fontSize = dimens.caption,
                    color = colors.textMuted,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** t29：首屏骨架卡（纯色块，无动画；数据到达后被真实卡片替换）。 */
@Composable
fun SkeletonPosterCard(
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
) {
    val colors = LocalTvColors.current
    Column(modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(colors.surfaceHigh),
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(14.dp).background(colors.surface))
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(12.dp).background(colors.surface))
    }
}

@Composable
fun PosterRow(
    title: String,
    items: List<VodSummary>,
    onItemClick: (VodSummary) -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    posterWidth: Dp = 150.dp,
) {
    val dimens = LocalTvDimens.current
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = title,
            subtitle = "${items.size} 部",
            actionText = if (onMore != null) "更多 ›" else null,
            onAction = onMore,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
        androidx.compose.runtime.CompositionLocalProvider(
            LocalListScrolling provides rowState.isScrollInProgress,
        ) {
            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            ) {
                items(
                    items = items,
                    key = { "${it.sourceId}|${it.vodId}" },
                    contentType = { "poster" },
                ) { vod ->
                    PosterCard(
                        vod = vod,
                        onClick = { onItemClick(vod) },
                        width = posterWidth,
                    )
                }
            }
        }
        if (items.isEmpty()) {
            TvLabel("暂无内容", dimens.body, LocalTvColors.current.textMuted, modifier = Modifier.padding(8.dp))
        }
    }
}

/** C 模式文字列表行：缩略图 + 两行文字（t29：支持 96×128 / 72×96 与 96dp 行高，左侧红条焦点）。 */
@Composable
fun LiteListRow(
    vod: VodSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    thumbWidth: Dp = 96.dp,
    thumbHeight: Dp = 128.dp,
    rowHeight: Dp? = null,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    TvFocusable(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        borderWidth = 0.dp,
        onClick = onClick,
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (rowHeight != null) Modifier.height(rowHeight) else Modifier)
                .background(if (focused) colors.surfaceHigh else colors.bg)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // t29：左侧 4dp 红条焦点（零动画）
            Box(
                Modifier
                    .width(4.dp)
                    .height(rowHeight ?: 88.dp)
                    .background(if (focused) colors.accent else Color.Transparent),
            )
            Spacer(Modifier.width(8.dp))
            PosterImage(
                url = vod.pic,
                thumbUrl = vod.picThumb,
                modifier = Modifier.size(width = thumbWidth, height = thumbHeight),
                requestWidth = thumbWidth.value.toInt().coerceAtLeast(1),
                requestHeight = thumbHeight.value.toInt().coerceAtLeast(1),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                TvLabel(
                    text = vod.name,
                    fontSize = dimens.cardTitle,
                    color = if (focused) colors.accent else colors.text,
                    weight = FontWeight.Medium,
                    maxLines = if (LocalTvTier.current.isLarge) 2 else 1,
                )
                TvLabel(
                    text = listOfNotNull(
                        badge,
                        vod.year.takeIf { it.isNotBlank() },
                        vod.remarks.takeIf { it.isNotBlank() },
                        vod.typeName.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    fontSize = dimens.caption,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            if (vod.sourceCount > 1) {
                TvLabel(
                    text = "${vod.sourceCount}源",
                    fontSize = dimens.caption,
                    color = colors.accent,
                    maxLines = 1,
                    weight = FontWeight.Bold,
                )
            }
        }
    }
}
