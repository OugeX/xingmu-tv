package com.tvbox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.desktop.data.Episode
import com.tvbox.desktop.data.VodDetail
import com.tvbox.desktop.data.VodRef

@Composable
fun InfoPanel(
    vm: AppViewModel,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(AppColors.surface)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
    ) {
        when {
            vm.player.state.active -> PlaybackInfo(vm, onToggleFullscreen)
            vm.section == Section.LIVE -> ChannelInfo(vm)
            vm.section.isBrowse || vm.section == Section.SEARCH -> VodInfo(vm)
            vm.section == Section.SOURCES -> SourceHelpInfo(vm)
            else -> SettingsHelpInfo(vm)
        }
    }
}

@Composable
private fun ChannelInfo(vm: AppViewModel) {
    val channel = vm.selectedChannel
    if (channel == null) {
        EmptyState("选择一个频道", "左侧列表点击频道，右侧显示可用线路")
        return
    }
    Text("CHANNEL", style = AppText.section)
    Spacer(Modifier.height(8.dp))
    Text(channel.name, style = AppText.title.copy(fontSize = 21.sp))
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tag(channel.group)
        Tag(channel.qualityLabel, if (channel.qualityLabel.contains("1080") || channel.qualityLabel.contains("2160")) AppColors.accent else AppColors.textMuted)
        Tag(channel.status, statusColor(channel.status))
    }
    if (!channel.desc.isNullOrBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(channel.desc, style = AppText.body.copy(fontSize = 12.sp))
    }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton("播放主线路", { vm.playLive(channel) }, Modifier.weight(1f))
        GhostButton(
            if (vm.isLiveFavorite(channel.id)) "★ 已收藏" else "☆ 收藏",
            { vm.toggleLiveFavorite(channel) },
        )
    }
    Spacer(Modifier.height(14.dp))

    Text("可用线路 · ${channel.lines.size}（按实测清晰度排序）", style = AppText.caption)
    Spacer(Modifier.height(6.dp))
    channel.lines.forEachIndexed { index, line ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .background(AppColors.surfaceHigh, RoundedCornerShape(6.dp))
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "线路 ${index + 1} · ${line.quality ?: line.tier ?: "未知"}${if (line.fallbackOnly) " · 备用" else ""}",
                    style = AppText.bodyStrong.copy(fontSize = 11.sp),
                )
                Text(
                    line.url,
                    style = AppText.mono.copy(fontSize = 9.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            GhostButton("播放", { vm.playLive(channel, index) })
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(
        "播放失败会自动软解，再失败自动换更低清晰度线路。",
        style = AppText.caption.copy(fontSize = 10.sp),
    )
}

@Composable
private fun VodInfo(vm: AppViewModel) {
    val summary = vm.selectedVod
    if (vm.detailLoading) {
        LoadingRow("加载详情…")
    }
    val detail = vm.detail
    vm.detailError?.let {
        Banner(it)
        Spacer(Modifier.height(8.dp))
    }
    if (detail == null) {
        if (!vm.detailLoading) {
            if (summary != null) {
                Text(summary.name, style = AppText.title)
                Spacer(Modifier.height(6.dp))
                Text("${summary.year} · ${summary.typeName} · ${summary.remarks}", style = AppText.caption)
            } else {
                EmptyState("选择一部作品", "左侧点选海报，这里显示详情、源与剧集")
            }
        }
        return
    }
    Row {
        RemoteImage(
            detail.pic,
            Modifier.width(104.dp).height(156.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(detail.name, style = AppText.title.copy(fontSize = 20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(
                    detail.year.takeIf { it.isNotBlank() },
                    detail.area.takeIf { it.isNotBlank() },
                    detail.typeName.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = AppText.caption,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(detail.sourceName, AppColors.accent)
                if (detail.score.isNotBlank() && detail.score != "0.0") Tag("评分 ${detail.score}")
                if (summary != null && summary.refs.size > 1) Tag("${summary.refs.size} 源可用", AppColors.success)
            }
            Spacer(Modifier.height(8.dp))
            Text(detail.remarks.ifBlank { "—" }, style = AppText.caption, maxLines = 1)
        }
    }
    if (detail.content.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        var expanded by remember(detail.vodId) { mutableStateOf(false) }
        Text(
            detail.content,
            style = AppText.body.copy(fontSize = 11.sp, lineHeight = 17.sp),
            maxLines = if (expanded) 40 else 5,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.content.length > 160) {
            Text(
                if (expanded) "收起" else "展开简介",
                style = AppText.caption.copy(color = AppColors.accent),
                modifier = Modifier.clickable { expanded = !expanded }.padding(top = 4.dp),
            )
        }
    }

    if (detail.actors.isNotBlank() || detail.director.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        if (detail.director.isNotBlank()) Text("导演：${detail.director}", style = AppText.caption, maxLines = 2)
        if (detail.actors.isNotBlank()) Text("主演：${detail.actors}", style = AppText.caption, maxLines = 3)
    }

    Spacer(Modifier.height(14.dp))
    val progress = vm.progressFor(detail.sourceId, detail.vodId)
    val favorite = vm.isVodFavorite(detail.sourceId, detail.vodId)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (progress != null) {
            PrimaryButton(
                "继续观看 · 第${progress.episodeIndex}集 ${formatTime(progress.positionMs)}",
                { vm.resumeCurrentDetail() },
                Modifier.weight(1f),
            )
        } else {
            PrimaryButton("播放第 1 集", { vm.playFirstEpisode() }, Modifier.weight(1f))
        }
        GhostButton(
            if (favorite) "★ 已收藏" else "☆ 收藏",
            { vm.toggleVodFavorite(detail.sourceId, detail.vodId, detail.name, detail.pic) },
        )
    }
    Spacer(Modifier.height(10.dp))

    if (summary != null && summary.refs.size > 1) {
        Text("切换源", style = AppText.caption)
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            summary.refs.forEach { ref ->
                val active = detail.sourceId == ref.sourceId
                Chip(
                    text = ref.sourceName,
                    selected = active,
                    onClick = { if (!active) vm.selectVod(summary, ref) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    val from = detail.playFroms.firstOrNull()
    val episodes = from?.let { detail.episodesByFrom[it] }.orEmpty()
    if (episodes.isNotEmpty()) {
        var expanded by remember(detail.vodId, from) { mutableStateOf(false) }
        Text("选集 · $from（${episodes.size} 集）", style = AppText.caption)
        Spacer(Modifier.height(6.dp))
        val shown = if (expanded) episodes else episodes.take(24)
        shown.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { episode ->
                    EpisodeChip(
                        episode = episode,
                        playing = vm.player.state.active && vm.playingEpisodeTitle == episode.title,
                        watched = episode.index in vm.watchedEpisodes,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.startVod(episode, detail) },
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (episodes.size > 24) {
            Spacer(Modifier.height(4.dp))
            GhostButton(
                if (expanded) "收起" else "展开全部 ${episodes.size} 集",
                { expanded = !expanded },
            )
        }
    } else {
        Text("该源没有解析到剧集地址", style = AppText.caption, color = AppColors.danger)
    }
}

@Composable
private fun EpisodeChip(
    episode: Episode,
    playing: Boolean,
    watched: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .background(
                if (playing) AppColors.accent.copy(alpha = 0.18f) else AppColors.surfaceHigh,
                RoundedCornerShape(5.dp),
            )
            .border(
                1.dp,
                if (playing) AppColors.accent.copy(alpha = 0.7f) else AppColors.divider,
                RoundedCornerShape(5.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            episode.title + if (watched) " ✓" else "",
            style = AppText.caption.copy(
                fontSize = 10.sp,
                color = when {
                    playing -> AppColors.accent
                    watched -> AppColors.success
                    else -> AppColors.textSecondary
                },
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlaybackInfo(vm: AppViewModel, onToggleFullscreen: () -> Unit) {
    val state = vm.player.state
    Text("NOW PLAYING", style = AppText.section)
    Spacer(Modifier.height(8.dp))
    Text(state.title, style = AppText.title.copy(fontSize = 19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(4.dp))
    Text(state.subtitle, style = AppText.caption)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tag(if (state.isLive) "直播" else "点播", AppColors.accent)
        if (state.attemptLabel.isNotBlank()) Tag(state.attemptLabel, AppColors.textSecondary)
        if (state.usedFallback) Tag("已自动降级", AppColors.warning)
    }
    Spacer(Modifier.height(12.dp))
    state.notice?.let {
        Banner(it, AppColors.warning)
        Spacer(Modifier.height(8.dp))
    }
    state.error?.let {
        Banner(it, AppColors.danger)
        Spacer(Modifier.height(8.dp))
    }
    Text("播放状态", style = AppText.caption)
    Spacer(Modifier.height(4.dp))
    Text(
        buildString {
            append(if (state.playing) "播放中" else if (state.buffering) "缓冲中" else "已暂停/停止")
            append(" · ${state.volume}% 音量 · ${state.rate}×")
            if (state.videoWidth > 0) append(" · ${state.videoWidth}×${state.videoHeight}")
        },
        style = AppText.body.copy(fontSize = 11.sp),
    )
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(if (vm.player.state.paused) "继续" else "暂停", { vm.togglePause() })
        GhostButton(if (vm.player.state.isLive) "全屏" else "全屏", onToggleFullscreen)
    }
    Spacer(Modifier.height(8.dp))
    GhostButton("停止播放", vm::stopPlayback, danger = true)

    if (state.attempts.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("自动降级顺序（${state.attempts.size}）", style = AppText.caption)
        Spacer(Modifier.height(5.dp))
        state.attempts.forEachIndexed { index, label ->
            val current = index == state.attemptIndex
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(
                            when {
                                current && state.usedFallback -> AppColors.warning
                                current -> AppColors.accent
                                index < state.attemptIndex -> AppColors.danger
                                else -> AppColors.divider
                            },
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    style = AppText.caption.copy(
                        fontSize = 10.sp,
                        color = if (current) AppColors.text else AppColors.textMuted,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    val detail = vm.detail
    if (!state.isLive && detail != null) {
        Spacer(Modifier.height(16.dp))
        val from = detail.playFroms.firstOrNull()
        val episodes = from?.let { detail.episodesByFrom[it] }.orEmpty()
        if (episodes.isNotEmpty()) {
            Text("快速切集 · $from", style = AppText.caption)
            Spacer(Modifier.height(5.dp))
            episodes.take(8).chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { episode ->
                        EpisodeChip(
                            episode = episode,
                            playing = vm.playingEpisodeTitle == episode.title,
                            watched = episode.index in vm.watchedEpisodes,
                            modifier = Modifier.weight(1f),
                            onClick = { vm.startVod(episode, detail) },
                        )
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("快捷键", style = AppText.caption)
    Text("空格 暂停/继续 · ← → 快退/快进 10s", style = AppText.caption.copy(fontSize = 10.sp))
    Text("↑ ↓ 音量 · F 全屏 · Esc 退出全屏/停止播放", style = AppText.caption.copy(fontSize = 10.sp))
}

@Composable
private fun SourceHelpInfo(vm: AppViewModel) {
    Text("SOURCE PROTOCOL", style = AppText.section)
    Spacer(Modifier.height(8.dp))
    Text("tvapp.source.v1.1", style = AppText.title.copy(fontSize = 18.sp))
    Spacer(Modifier.height(8.dp))
    Text(
        "支持 maccms / m3u / bundle 三种 kind。导入时会按 tools/validate_sources.py 的同一套错误码校验，失败不会写入磁盘。",
        style = AppText.body.copy(fontSize = 11.sp),
    )
    Spacer(Modifier.height(12.dp))
    Text("错误码", style = AppText.caption)
    listOf(
        "E_JSON" to "JSON 语法错误",
        "E_SCHEMA" to "字段/类型/枚举不合法",
        "E_ID" to "源 id 或端点重复",
        "E_URL" to "不是 http(s) 地址",
        "E_FIELD" to "v1.1 字段语义错误",
        "E_EPG" to "节目单地址不合法",
        "E_ADBLOCK" to "广告规则非法或拦截自身端点",
        "E_CHANNEL" to "频道定义非法/重复",
        "E_REGEX" to "过滤正则无效",
        "E_NESTED" to "bundle 内嵌套 bundle",
        "E_NETWORK" to "桌面端下载导入源失败（本地校验用）",
    ).forEach { (code, desc) ->
        Row(Modifier.padding(vertical = 2.dp)) {
            Text(code, style = AppText.mono.copy(color = AppColors.accent), modifier = Modifier.width(84.dp))
            Text(desc, style = AppText.caption, modifier = Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(14.dp))
    Text("内置源：${vm.sources.size} 个（${vm.builtinIssueCount} 条校验警告）", style = AppText.caption)
    Text("内置文件：${vm.paths.assetsSource}", style = AppText.caption.copy(fontSize = 10.sp))
}

@Composable
private fun SettingsHelpInfo(vm: AppViewModel) {
    Text("PLAYBACK", style = AppText.section)
    Spacer(Modifier.height(8.dp))
    Text("libVLC 播放器", style = AppText.title.copy(fontSize = 18.sp))
    Spacer(Modifier.height(8.dp))
    Text(vm.vlcMessage, style = AppText.body.copy(fontSize = 11.sp))
    Spacer(Modifier.height(12.dp))
    Text(
        "点播与直播共用同一个 libVLC 实例；1080p 黑屏时按「硬解 → 软解 → 降清晰度线路」自动降级。",
        style = AppText.caption,
    )
    Spacer(Modifier.height(12.dp))
    Text("配置目录", style = AppText.caption)
    Text(vm.paths.configDir.toString(), style = AppText.mono.copy(fontSize = 10.sp))
    Spacer(Modifier.height(8.dp))
    Text("缓存目录", style = AppText.caption)
    Text(vm.paths.cacheDir.toString(), style = AppText.mono.copy(fontSize = 10.sp))
}
