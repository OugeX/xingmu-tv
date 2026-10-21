package com.tvbox.desktop.player

import com.tvbox.desktop.data.PlayLine
import com.tvbox.desktop.data.PlaybackRequest

/**
 * 播放降级阶梯（v0.1 需求：1080p 源在部分机器黑屏时自动降级）。
 *
 * 每个播放请求展开成有序尝试：
 *  - 直播：按源线路清晰度从高到低，每条线路先硬解、再软解；
 *  - 点播：主线路先硬解后软解，然后依次换同集的其他线路（play_from）。
 *
 * “软解”（`:avcodec-hw=none`）是 1080p 黑屏最常见的兜底；软解仍黑屏才换更低清晰度线路。
 */
object PlaybackLadder {

    data class Attempt(
        val url: String,
        val lineLabel: String,
        val quality: String?,
        val softwareDecode: Boolean,
        val lineIndex: Int,
    ) {
        val label: String
            get() = buildString {
                append(lineLabel)
                if (!quality.isNullOrBlank()) append(" · ").append(quality)
                append(if (softwareDecode) " · 软解" else " · 硬解")
            }
    }

    fun build(request: PlaybackRequest, hardwareDecode: Boolean, maxQuality: String): List<Attempt> {
        val filtered = applyQualityCeiling(request.lines, maxQuality)
        val lines = filtered.ifEmpty { request.lines }
        val result = ArrayList<Attempt>(lines.size * 2)
        lines.forEachIndexed { index, line ->
            val label = if (request.isLive) "线路 ${index + 1}" else line.label
            if (hardwareDecode) {
                result += Attempt(line.url, label, line.quality, softwareDecode = false, lineIndex = index)
            }
            result += Attempt(line.url, label, line.quality, softwareDecode = true, lineIndex = index)
        }
        return result.distinctBy { it.url to it.softwareDecode }.take(MAX_ATTEMPTS)
    }

    /**
     * 画质上限：auto 不限制；1080p/720p/480p 时剔除更高画质线路（未知画质保留，避免误删）。
     * 如果全部被剔除，则保留原始线路（宁可能播，不空着）。
     */
    private fun applyQualityCeiling(lines: List<PlayLine>, maxQuality: String): List<PlayLine> {
        val ceiling = when (maxQuality.lowercase()) {
            "1080p" -> 5
            "720p" -> 4
            "480p" -> 2
            else -> return lines
        }
        return lines.filter { line ->
            val rank = rankOf(line.quality)
            rank <= 0 || rank <= ceiling
        }
    }

    private fun rankOf(quality: String?): Int {
        val q = quality?.lowercase() ?: return 0
        return when {
            q.startsWith("4k") || q.startsWith("2160") -> 6
            q.startsWith("1080") -> 5
            q.startsWith("720") -> 4
            q.startsWith("576") -> 3
            q.startsWith("480") -> 2
            q.startsWith("360") -> 1
            else -> 0
        }
    }

    private const val MAX_ATTEMPTS = 12
}
