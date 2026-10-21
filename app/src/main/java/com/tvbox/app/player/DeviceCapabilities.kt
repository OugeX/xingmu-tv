package com.tvbox.app.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.media3.common.MimeTypes
import com.tvbox.app.core.TvLog

/**
 * 启动时一次性的解码能力探测（PLAYBACK_SPEC §1 保险 2、AVSYNC_SPEC §1、LIVE_QUALITY 4K 规则）。
 *
 * 只在 IO 侧调用一次并缓存；结果用于：
 * - HEVC 不支持时优先选 H.264 变体，而不是“先播了再卡”；
 * - 1080p H.264 都不支持的超低端设备，把画质上限压到 720p；
 * - 4K 源（3840×2160@25）必须同时通过 MediaCodec 能力探测才允许选，否则直接跳过。
 *
 * 注意：只查 `REGULAR_CODECS`（系统实际会用的硬解），不枚举软件解码器。
 */
object DeviceCapabilities {

    data class Caps(
        val h264_1080p: Boolean,
        val hevc_1080p: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
        val avc4k25: Boolean = false,
        val hevc4k25: Boolean = false,
        /** 存在 OMX.MS.*（MStar）解码器：>720p 纹理路径可能整机黑屏 */
        val hasMstarDecoder: Boolean = false,
    ) {
        /** 低端默认上限：HEVC 不稳时守住 H.264；连 1080p H.264 都不支持则压 720p。 */
        val defaultMaxHeight: Int
            get() = if (h264_1080p) 1080 else 720

        /** 设备是否有任一 4K 解码路径（H.264/HEVC 3840×2160@25）。 */
        val supports4K: Boolean get() = avc4k25 || hevc4k25

        /** 某条源在当前设备上能否解码：4K 源必须匹配编码能力，其余沿用 1080p 上限。 */
        fun supportsLine(codec: String?, height: Int): Boolean {
            if (height < 2000) return true
            return when (codec?.trim()?.lowercase()) {
                "hevc", "h265" -> hevc4k25
                "h264", "avc", null -> avc4k25
                else -> false
            }
        }

        val hevcNote: String
            get() = if (hevc_1080p) "HEVC 支持" else "HEVC 不支持，已优先 H.264"

        val fourKNote: String
            get() = when {
                avc4k25 && hevc4k25 -> "4K H.264/HEVC 均支持"
                avc4k25 -> "4K H.264 支持"
                hevc4k25 -> "4K HEVC 支持"
                else -> "不支持 4K，已跳过 4K 源"
            }
    }

    @Volatile
    private var cached: Caps? = null

    val caps: Caps
        get() = cached ?: probe().also { cached = it }

    fun probe(): Caps {
        var h264 = false
        var hevc = false
        var avc4k = false
        var hevc4k = false
        var hasMstar = false
        var maxW = 0
        var maxH = 0
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                if (info.name.startsWith("OMX.MS.", ignoreCase = true)) hasMstar = true
                val types = info.supportedTypes
                val avcCaps = videoCaps(info, MimeTypes.VIDEO_H264, types)
                val hevcCaps = videoCaps(info, MimeTypes.VIDEO_H265, types)
                if (avcCaps != null) {
                    h264 = h264 || runCatching { avcCaps.isSizeSupported(1920, 1080) }.getOrDefault(false)
                    avc4k = avc4k || supports4k(avcCaps)
                    maxW = maxOf(maxW, runCatching { avcCaps.supportedWidths.upper }.getOrDefault(0))
                    maxH = maxOf(maxH, runCatching { avcCaps.supportedHeights.upper }.getOrDefault(0))
                }
                if (hevcCaps != null) {
                    hevc = hevc || runCatching { hevcCaps.isSizeSupported(1920, 1080) }.getOrDefault(false)
                    hevc4k = hevc4k || supports4k(hevcCaps)
                    maxW = maxOf(maxW, runCatching { hevcCaps.supportedWidths.upper }.getOrDefault(0))
                    maxH = maxOf(maxH, runCatching { hevcCaps.supportedHeights.upper }.getOrDefault(0))
                }
            }
        } catch (t: Throwable) {
            TvLog.w("Capabilities", "probe failed: ${t.message}")
        }
        val result = Caps(h264, hevc, maxW, maxH, avc4k, hevc4k, hasMstar)
        TvLog.i(
            "Capabilities",
            "h264_1080p=$h264 hevc_1080p=$hevc avc4k=$avc4k hevc4k=$hevc4k mstar=$hasMstar " +
                "max=${maxW}x$maxH defaultCap=${result.defaultMaxHeight}p",
        )
        return result
    }

    private fun videoCaps(
        info: MediaCodecInfo,
        mime: String,
        supportedTypes: Array<String>,
    ): MediaCodecInfo.VideoCapabilities? {
        if (supportedTypes.none { it.equals(mime, ignoreCase = true) }) return null
        return runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
    }

    private fun supports4k(caps: MediaCodecInfo.VideoCapabilities): Boolean = runCatching {
        caps.isSizeSupported(3840, 2160) && caps.areSizeAndRateSupported(3840, 2160, 25.0)
    }.getOrDefault(false)
}
