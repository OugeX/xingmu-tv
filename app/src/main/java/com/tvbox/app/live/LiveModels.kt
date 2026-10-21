package com.tvbox.app.live

import androidx.compose.runtime.Immutable
import com.tvbox.app.data.Channel
import com.tvbox.app.data.ChannelUrl
import com.tvbox.app.player.PlaybackInfo

/**
 * 直播层模型：在冻结的 [Channel] 之上补 UI/播放需要的排序、台号、健康度与线路信息。
 * `Channel` 本身不允许改字段（t9 只消费 data 层契约）。
 */

/** 一条可播放线路（对应 seed 的 source 对象；fallbackOnly 只做兜底）。 */
@Immutable
data class LiveLine(
    val index: Int,
    val url: String,
    val quality: String? = null,
    val resolution: String? = null,
    val latencyMs: Int? = null,
    val fallbackOnly: Boolean = false,
    /** live_seed 实测字段（PyAV 读流头，2026-10-21） */
    val tier: String? = null,
    val measuredWidth: Int = 0,
    val measuredHeight: Int = 0,
    val measuredCodec: String? = null,
    val measuredFps: Float = 0f,
    val bitrateBps: Int = 0,
) {
    val label: String
        get() = buildString {
            append("线路").append(index + 1)
            if (displayHeight > 0) append(" · ").append(displayHeight).append("p")
            if (fallbackOnly) append("（备用）")
        }

    /** 实测高度优先；没有实测时从 resolution/quality 兜底。 */
    val displayHeight: Int
        get() {
            if (measuredHeight > 0) return measuredHeight
            resolution?.let { res ->
                val h = res.substringAfter('x', "").toIntOrNull()
                if (h != null && h > 0) return h
            }
            return quality?.removeSuffix("p")?.toIntOrNull() ?: 0
        }

    val is4k: Boolean get() = displayHeight >= 2000

    val bitrateText: String
        get() = if (bitrateBps > 0) String.format("%.1fMbps", bitrateBps / 1_000_000f) else "—"

    val codecLabel: String
        get() = when (measuredCodec?.lowercase()) {
            "h264", "avc" -> "H.264"
            "hevc", "h265" -> "HEVC"
            "mpeg2video" -> "MPEG-2"
            null -> "—"
            else -> measuredCodec
        }

    /** 选源排序：4K > 1080p > 720p > 576p > 480p > ≤360p/unknown > error/无视频轨 */
    val rank: Int
        get() = when (tier?.lowercase()) {
            "4k", "2160p" -> 6
            "1080p" -> 5
            "720p" -> 4
            "576p" -> 3
            "480p" -> 2
            "≤360p", "≤360", "360p", "unknown" -> 1
            "error", "无视频轨", "novideo" -> -1
            else -> if (displayHeight > 0) 2 else 1
        }

    /** 自动选源是否可用：探测失败/无视频轨永不自动选（任务 §4）。 */
    val autoPlayable: Boolean get() = rank > 0

    val measuredSummary: String
        get() = "${displayHeight}p · $bitrateText · $codecLabel"
}

/** 频道健康度（seed status；degraded 表示只有 fallback_only 源）。 */
enum class LiveHealth(val label: String) {
    HEALTHY("正常"),
    FLAKY("线路波动"),
    DEGRADED("线路不稳"),
    UNKNOWN("未知");

    val unstable: Boolean get() = this == FLAKY || this == DEGRADED

    companion object {
        fun from(raw: String?): LiveHealth = when (raw?.trim()?.lowercase()) {
            "healthy", "ok", "good" -> HEALTHY
            "flaky" -> FLAKY
            "degraded" -> DEGRADED
            else -> UNKNOWN
        }
    }
}

/** 分组（seed groups：央视频道 / 卫视频道 / 地方频道）。 */
@Immutable
data class LiveGroup(
    val id: String,
    val name: String,
    val order: Int,
    val channelCount: Int = 0,
)

/** 直播频道：种子字段 + 播放需要的排序/台号/线路。 */
@Immutable
data class LiveChannel(
    val channel: Channel,
    val groupId: String,
    val groupName: String,
    val order: Int,
    /** 数字键直达台号：按“分组顺序 + 组内顺序”全量编号，从 1 开始 */
    val number: Int,
    val health: LiveHealth = LiveHealth.UNKNOWN,
    val quality: String? = null,
    val sourceCount: Int = 0,
    val latencyMs: Int? = null,
    /** 主用线路在前、fallback_only 在后，顺序即 priority */
    val lines: List<LiveLine> = emptyList(),
    /** 频道最佳实测档（seed best_tier/best_height） */
    val bestTier: String? = null,
    val bestHeight: Int = 0,
    /** 属于 live_hd.json「高清专区」 */
    val hdZone: Boolean = false,
) {
    val id: String get() = channel.id
    val name: String get() = channel.name
    val group: String get() = channel.group
    val logo: String? get() = channel.logo
    val tvgId: String? get() = channel.tvgId
    val desc: String? get() = channel.desc
    val numberText: String get() = "%02d".format(number)
    val healthyLines: List<LiveLine> get() = lines.filterNot { it.fallbackOnly }
    val fallbackLines: List<LiveLine> get() = lines.filter { it.fallbackOnly }
    val hasPlayableLine: Boolean get() = lines.any { it.autoPlayable }

    /** 按实测档排序（同档保持 seed priority 顺序），fallback_only 永远排最后。 */
    val rankedLines: List<LiveLine>
        get() = lines.sortedWith(
            compareBy<LiveLine>({ if (it.fallbackOnly) 1 else 0 }, { -it.rank }, { it.index }),
        )

    val bestLine: LiveLine? get() = rankedLines.firstOrNull { it.autoPlayable }

    val bestLabel: String
        get() = when {
            bestHeight > 0 -> "${bestHeight}p"
            bestTier != null -> bestTier
            bestLine != null -> "${bestLine!!.displayHeight}p"
            else -> "—"
        }

    /** EPG 匹配键：tvg_id 优先，其次 id / 台名（全部小写）。 */
    val epgKeys: List<String>
        get() = listOf(tvgId, id, name)
            .filterNotNull()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun toChannelUrl(): List<ChannelUrl> = channel.urls
}

/** 播放中/列表右侧的频道详情（PLAYBACK_SPEC §3 所有字段）。 */
@Immutable
data class LiveChannelInfo(
    val channel: LiveChannel,
    val lineIndex: Int,
    val info: PlaybackInfo,
    val measuredLatencyMs: Long = 0L,
    val epg: EpgNowNext = EpgNowNext.EMPTY,
) {
    val lineCount: Int get() = channel.lines.size
    val backupLineCount: Int get() = channel.lines.size - 1
    val channelName: String get() = channel.name
    val groupName: String get() = channel.groupName
    val logoUrl: String? get() = channel.logo
    val lineText: String get() = "线路 ${lineIndex + 1}/$lineCount"
    val currentLine: LiveLine? get() = channel.lines.getOrNull(lineIndex)
    val lineLatencyText: String
        get() {
            val measured = measuredLatencyMs.takeIf { it > 0 }
            val fallback = currentLine?.latencyMs?.toLong()
            val value = measured ?: fallback ?: return "—"
            return "${value}ms"
        }
    val resolutionText: String get() = info.resolutionText
    val bitrateText: String get() = info.bitrateText
    val codecText: String get() = info.codecText
    val qualitySourceText: String
        get() = currentLine?.quality?.takeIf { it != "未知" }
            ?: channel.quality
            ?: "—"

    /** HUD 规格：`1080p · 5.9Mbps · 硬解 H.264`（实测字段优先，回落 TracksInfo）。 */
    val measuredText: String
        get() {
            val line = currentLine
            val height = line?.displayHeight?.takeIf { it > 0 } ?: info.height
            val bitrate = line?.bitrateBps?.takeIf { it > 0 } ?: info.videoBitrate
            val codec = line?.codecLabel?.takeIf { it != "—" } ?: info.codecText
            val decoder = when {
                info.decoderName == null -> "—"
                info.decoderName!!.startsWith("OMX") || info.decoderName!!.startsWith("c2.") -> "硬解"
                else -> "软解"
            }
            val bitrateText = if (bitrate > 0) String.format("%.1fMbps", bitrate / 1_000_000f) else "—"
            val heightText = if (height > 0) "${height}p" else "—"
            return "$heightText · $bitrateText · $decoder $codec"
        }
    val descText: String get() = channel.desc?.takeIf { it.isNotBlank() } ?: "暂无频道简介"
    val epgText: String get() = epg.text
    val epgProgress: Float get() = epg.progress(System.currentTimeMillis())
}

/** 浮层状态（MENU 九宫格 / INFO 详情）。 */
enum class LiveOverlay { NONE, CHANNEL_GRID, CHANNEL_INFO }

/** 直播页完整状态（单一 StateFlow，UI 只订阅它）。 */
@Immutable
data class LiveState(
    val loading: Boolean = true,
    val seedAvailable: Boolean = false,
    val seedSource: String = "",
    val groups: List<LiveGroup> = emptyList(),
    val channels: List<LiveChannel> = emptyList(),
    val groupedChannels: Map<String, List<LiveChannel>> = emptyMap(),
    val current: LiveChannel? = null,
    val lineIndex: Int = 0,
    val switching: Boolean = false,
    val switchingStartedAt: Long = 0L,
    val switchLatencyMs: Long = 0L,
    val overlay: LiveOverlay = LiveOverlay.NONE,
    val numberEntry: String = "",
    val message: String? = null,
    val error: String? = null,
    val info: PlaybackInfo = PlaybackInfo(),
    val epg: EpgNowNext = EpgNowNext.EMPTY,
    /** 「4K 优先」设置（默认关，仅设备支持时生效） */
    val prefer4K: Boolean = false,
    /** 「强制 1080p」（默认关；API≤23 默认 720p 安全模式） */
    val force1080: Boolean = false,
    val deviceSupports4K: Boolean = false,
) {
    val lineCount: Int get() = current?.lines?.size ?: 0
    val currentLine: LiveLine? get() = current?.lines?.getOrNull(lineIndex)
    val channelInfo: LiveChannelInfo?
        get() = current?.let {
            LiveChannelInfo(
                channel = it,
                lineIndex = lineIndex,
                info = info,
                measuredLatencyMs = switchLatencyMs,
                epg = epg,
            )
        }

    val canSeekBack: Boolean get() = info.isLive && info.canSeekBack
    val dvrWindowMs: Long get() = info.dvrWindowMs
    val statusText: String
        get() = when {
            loading -> "正在加载频道…"
            error != null -> error
            switching -> "正在起播 ${current?.name ?: ""}…"
            current != null -> "${current.name} · ${channelInfo?.measuredText ?: "${info.resolutionText} · ${info.bitrateText}"}"
            else -> "暂无频道"
        }
}

/** EPG 单档节目。 */
@Immutable
data class EpgProgram(
    val channelId: String,
    val title: String,
    val startMs: Long,
    val stopMs: Long,
    val desc: String? = null,
) {
    val durationMs: Long get() = (stopMs - startMs).coerceAtLeast(0L)

    fun isNow(nowMs: Long): Boolean = nowMs in startMs until stopMs

    fun progress(nowMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return ((nowMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
    }
}

/** 正在播 / 下一档（无 EPG 时明确显示「暂无节目单」而不是空白）。 */
@Immutable
data class EpgNowNext(
    val channelId: String? = null,
    val now: EpgProgram? = null,
    val next: EpgProgram? = null,
    val updatedAtMs: Long = 0L,
) {
    val available: Boolean get() = now != null || next != null

    val text: String
        get() = when {
            now != null && next != null -> "正在播：${now.title} · 下一档：${next.title}"
            now != null -> "正在播：${now.title}"
            next != null -> "下一档：${next.title}"
            else -> NO_EPG_TEXT
        }

    val nowTitle: String get() = now?.title ?: NO_EPG_TEXT
    val nextTitle: String get() = next?.title ?: "—"

    fun progress(nowMs: Long): Float = now?.progress(nowMs) ?: 0f

    companion object {
        const val NO_EPG_TEXT = "暂无节目单"
        val EMPTY = EpgNowNext()
    }
}
