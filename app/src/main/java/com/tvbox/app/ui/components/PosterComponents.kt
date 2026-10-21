package com.tvbox.app.ui.components

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tvbox.app.core.TvLog
import com.tvbox.app.data.AppImages
import com.tvbox.app.data.VodSummary
import com.tvbox.app.ui.ContentGate
import com.tvbox.app.ui.LocalTvColors
import com.tvbox.app.ui.LocalTvDimens
import com.tvbox.app.ui.LocalTvMode
import com.tvbox.app.ui.LocalTvTier
import com.tvbox.app.ui.TvMode

@Composable
fun PosterImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    requestWidth: Int = 320,
    requestHeight: Int = 450,
    fallbackText: String = "",
) {
    val context = LocalContext.current
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    val lite = LocalTvMode.current == TvMode.LITE
    val imageLoader = remember(context) { AppImages.loader(context) }

    // 失败自动重试：先原地址，再 http/https 互换一次，仍失败则显示占位图（不再留黑块）
    var attempt by remember(url) { mutableIntStateOf(0) }
    var failed by remember(url) { mutableStateOf(url.isBlank()) }
    val effectiveUrl = remember(url, attempt) {
        when (attempt) {
            0 -> url
            1 -> if (url.startsWith("https://")) url.replaceFirst("https://", "http://")
            else url.replaceFirst("http://", "https://")
            else -> url
        }
    }
    val request = remember(effectiveUrl, lite, requestWidth, requestHeight) {
        ImageRequest.Builder(context)
            .data(effectiveUrl)
            .size(requestWidth, requestHeight)
            .allowRgb565(true)
            .allowHardware(!lite)
            .crossfade(false)
            .build()
    }

    Box(modifier.background(colors.surface)) {
        if (!failed && effectiveUrl.isNotBlank() && !ContentGate.zapping && !com.tvbox.app.player.PlaybackMemoryGuard.pauseImages) {
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
                PosterImage(url = vod.pic, modifier = Modifier.fillMaxSize(), fallbackText = vod.name)
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
            TvLabel(
                text = buildString {
                    if (vod.year.isNotBlank()) append(vod.year)
                    if (vod.score.isNotBlank() && vod.score != "0.0") {
                        if (isNotEmpty()) append(" · ")
                        append(vod.score)
                    }
                },
                fontSize = dimens.caption,
                color = colors.textMuted,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        ) {
            items(items, key = { "${it.sourceId}|${it.vodId}" }) { vod ->
                PosterCard(
                    vod = vod,
                    onClick = { onItemClick(vod) },
                    width = posterWidth,
                )
            }
        }
        if (items.isEmpty()) {
            TvLabel("暂无内容", dimens.body, LocalTvColors.current.textMuted, modifier = Modifier.padding(8.dp))
        }
    }
}

/** C 模式文字列表行：112×63 缩略图 + 两行文字。 */
@Composable
fun LiteListRow(
    vod: VodSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val colors = LocalTvColors.current
    val dimens = LocalTvDimens.current
    TvFocusable(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        borderWidth = 2.dp,
        onClick = onClick,
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (focused) colors.surfaceHigh else colors.bg)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosterImage(
                url = vod.pic,
                modifier = Modifier
                    .size(width = 112.dp, height = 63.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(0.dp)),
                requestWidth = 112,
                requestHeight = 63,
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
