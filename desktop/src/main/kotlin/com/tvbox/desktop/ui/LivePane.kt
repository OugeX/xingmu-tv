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
import com.tvbox.desktop.data.LiveChannel

@Composable
fun LivePane(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 18.dp)) {
        SectionHeader(
            kicker = "LIVE",
            title = "直播频道",
            subtitle = "${vm.live?.channels?.size ?: 0} 个频道 · ${vm.live?.groups?.size ?: 0} 个分组 · ${vm.live?.source ?: ""}",
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
            Chip("全部", vm.liveGroupId == null, { vm.setLiveGroup(null) })
            vm.live?.groups?.forEach { group ->
                Chip("${group.name} ${group.count}", vm.liveGroupId == group.id, { vm.setLiveGroup(group.id) })
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("频道", style = AppText.caption, modifier = Modifier.weight(1f))
            Text("画质", style = AppText.caption, modifier = Modifier.width(64.dp))
            Text("线路", style = AppText.caption, modifier = Modifier.width(58.dp))
            Text("延迟", style = AppText.caption, modifier = Modifier.width(70.dp))
            Spacer(Modifier.width(64.dp))
        }
        Divider(color = AppColors.divider)

        val channels = vm.filteredChannels
        when {
            vm.live == null -> LoadingRow("加载直播频道…")
            channels.isEmpty() -> EmptyState("没有匹配的频道", "换个分组或搜索词试试")
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(channels, key = { it.id }) { channel ->
                    ChannelRow(
                        channel = channel,
                        selected = vm.selectedChannel?.id == channel.id,
                        playing = vm.player.state.active && vm.player.state.isLive && vm.playback?.title == channel.name,
                        onSelect = { vm.selectChannel(channel) },
                        onPlay = { vm.startLive(channel) },
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
    onSelect: () -> Unit,
    onPlay: () -> Unit,
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
