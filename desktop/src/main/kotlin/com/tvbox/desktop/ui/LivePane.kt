package com.tvbox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.desktop.data.LiveCategoryKind
import com.tvbox.desktop.data.LiveChannel

@Composable
fun LivePane(vm: AppViewModel) {
    val catalog = vm.liveCatalog
    val category = vm.currentCategory
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 18.dp)) {
        SectionHeader(
            kicker = "LIVE",
            title = "直播频道",
            subtitle = "${vm.liveChannelCount} 个频道 · ${catalog?.categories?.size ?: 0} 个分组 · ${vm.live?.source ?: ""}",
            actions = {
                SearchField(
                    value = vm.liveQuery,
                    onValueChange = { vm.liveQuery = it },
                    placeholder = "搜索频道",
                    modifier = Modifier.width(190.dp),
                )
                GhostButton("刷新", { vm.refresh() })
            },
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            catalog?.categories?.forEach { item ->
                val label = if (item.kind == LiveCategoryKind.HD) "高清专区 ${item.count}" else "${item.name} ${item.count}"
                Chip(label, vm.liveCategoryId == item.id, { vm.setLiveCategory(item.id) })
            }
        }

        if (vm.liveCategoryId == "local" && vm.localPageCount > 1) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("上一页", { vm.previousLocalPage() }, enabled = vm.localPage > 0)
                Text(
                    "第 ${vm.localPageNumber}/${vm.localPageCount} 页 · 每页 60 台",
                    style = AppText.caption,
                )
                GhostButton("下一页", { vm.nextLocalPage() }, enabled = vm.localPageNumber < vm.localPageCount)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("频道", style = AppText.caption, modifier = Modifier.weight(1f))
            Text("画质", style = AppText.caption, modifier = Modifier.width(64.dp))
            Text("线路", style = AppText.caption, modifier = Modifier.width(58.dp))
            Text("延迟", style = AppText.caption, modifier = Modifier.width(70.dp))
            Spacer(Modifier.width(138.dp))
        }
        Divider(color = AppColors.divider)

        val channels = vm.visibleChannels
        when {
            vm.live == null -> LoadingRow("加载直播频道…")
            channels.isEmpty() -> EmptyState(
                when (category?.kind) {
                    LiveCategoryKind.FAVORITES -> "还没有收藏频道"
                    LiveCategoryKind.RECENT -> "还没有直播观看记录"
                    else -> "没有匹配的频道"
                },
                if (category?.kind == LiveCategoryKind.FAVORITES || category?.kind == LiveCategoryKind.RECENT) {
                    "在右侧频道信息里点「收藏」，收看的频道会自动进入最近"
                } else {
                    "换个分组或搜索词试试"
                },
            )
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(channels, key = { it.id }) { channel ->
                    ChannelRow(
                        channel = channel,
                        selected = vm.selectedChannel?.id == channel.id,
                        playing = vm.player.state.active && vm.player.state.isLive && vm.playback?.title == channel.name,
                        favorite = vm.isLiveFavorite(channel.id),
                        onSelect = { vm.selectChannel(channel) },
                        onPlay = { vm.startLive(channel) },
                        onToggleFavorite = { vm.toggleLiveFavorite(channel) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: LiveChannel,
    selected: Boolean,
    playing: Boolean,
    favorite: Boolean,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    playing -> AppColors.accent.copy(alpha = 0.12f)
                    selected -> AppColors.surfaceHigh
                    else -> Color.Transparent
                },
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            channel.number.toString().padStart(3, '0'),
            style = AppText.mono.copy(color = if (selected) AppColors.accent else AppColors.textMuted),
            modifier = Modifier.width(40.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.name,
                    style = AppText.bodyStrong.copy(color = if (selected) AppColors.text else AppColors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playing) {
                    Spacer(Modifier.width(8.dp))
                    Tag("播放中", AppColors.accent)
                }
            }
            Text(
                channel.desc ?: channel.group,
                style = AppText.caption.copy(fontSize = 10.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.width(64.dp)) {
            Tag(channel.qualityLabel, if (channel.qualityLabel.contains("1080") || channel.qualityLabel.contains("2160")) AppColors.accent else AppColors.textMuted)
        }
        Text("${channel.lines.size}", style = AppText.caption, modifier = Modifier.width(58.dp))
        Box(Modifier.width(70.dp)) {
            val latency = channel.lines.firstOrNull()?.latencyMs
            Text(if (latency != null) "$latency ms" else "—", style = AppText.caption)
        }
        Box(Modifier.width(68.dp)) {
            GhostButton(if (favorite) "★ 已藏" else "☆ 收藏", onToggleFavorite)
        }
        Box(Modifier.width(64.dp)) {
            GhostButton("播放", onPlay)
        }
    }
}

@Composable
fun statusColor(status: String): Color = when (status.lowercase()) {
    "healthy" -> AppColors.success
    "flaky", "intermittent" -> AppColors.warning
    "degraded", "unstable", "error" -> AppColors.danger
    else -> AppColors.textMuted
}
