package com.tvbox.app.player

import android.annotation.SuppressLint

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import com.tvbox.app.core.TvLog
import java.nio.ByteBuffer

/**
 * 音频输出包装：不改解码、不改流，只做两件事——
 * 1. 把音画校准作为 **renderer 音频时钟偏移**（`getCurrentPositionUs ± offset`），视频渲染按该时钟出帧，
 *    即 HUD 的「视频偏移 −500…+500ms」；音频样本本身不重采样、不插静音（AVSYNC_SPEC §4 手动校准）。
 * 2. 把 [DefaultAudioSink] 原样暴露给控制器，便于把欠载事件与缓冲档位关联起来。
 *
 * 说明：偏移改的是“时钟读数”，因此 `PlayerController.positionMs` 在播放中也会包含该偏移（±0.5s），
 * UI 进度条按该值显示不会跳；退出播放器或重置为 0 即恢复。
 */
@UnstableApi
@SuppressLint("NewApi")
class CalibrationAudioSink(
    private val delegate: AudioSink,
) : AudioSink {

    @Volatile
    var videoOffsetMs: Int = 0
        private set

    fun applyVideoOffsetMs(offsetMs: Int) {
        videoOffsetMs = clampVideoOffset(offsetMs)
        TvLog.i(TAG, "video offset = $videoOffsetMs ms (renderer clock bias)")
    }

    override fun getCurrentPositionUs(playsUntilDiscontinuity: Boolean): Long {
        val base = delegate.getCurrentPositionUs(playsUntilDiscontinuity)
        if (base == AudioSink.CURRENT_POSITION_NOT_SET) return base
        return base + videoOffsetMs * 1_000L
    }

    override fun setListener(listener: AudioSink.Listener) = delegate.setListener(listener)

    override fun setPlayerId(playerId: PlayerId?) = delegate.setPlayerId(playerId)

    override fun setClock(clock: Clock) = delegate.setClock(clock)

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = delegate.getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        delegate.getFormatOffloadSupport(format)

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) =
        delegate.configure(format, specifiedBufferSize, outputChannels)

    override fun play() = delegate.play()

    override fun handleDiscontinuity() = delegate.handleDiscontinuity()

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, flags: Int): Boolean =
        delegate.handleBuffer(buffer, presentationTimeUs, flags)

    override fun playToEndOfStream() = delegate.playToEndOfStream()

    override fun isEnded(): Boolean = delegate.isEnded

    override fun hasPendingData(): Boolean = delegate.hasPendingData()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) =
        delegate.setPlaybackParameters(playbackParameters)

    override fun getPlaybackParameters(): PlaybackParameters = delegate.playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) =
        delegate.setSkipSilenceEnabled(skipSilenceEnabled)

    override fun getSkipSilenceEnabled(): Boolean = delegate.skipSilenceEnabled

    override fun setAudioAttributes(audioAttributes: AudioAttributes) =
        delegate.setAudioAttributes(audioAttributes)

    override fun getAudioAttributes(): AudioAttributes? = delegate.audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) = delegate.setAudioSessionId(audioSessionId)

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = delegate.setAuxEffectInfo(auxEffectInfo)

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) =
        delegate.setPreferredDevice(audioDeviceInfo)

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) =
        delegate.setOutputStreamOffsetUs(outputStreamOffsetUs)

    override fun enableTunnelingV21() = delegate.enableTunnelingV21()

    override fun disableTunneling() = delegate.disableTunneling()

    override fun setOffloadMode(offloadMode: Int) = delegate.setOffloadMode(offloadMode)

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) =
        delegate.setOffloadDelayPadding(delayInFrames, paddingInFrames)

    override fun setVolume(volume: Float) = delegate.setVolume(volume)

    override fun pause() = delegate.pause()

    override fun flush() = delegate.flush()

    override fun reset() = delegate.reset()

    override fun release() = delegate.release()

    companion object {
        private const val TAG = "AvSync"
    }
}

/**
 * RenderersFactory：所有音画同步开关都在这里落地（docs/AVSYNC_SPEC.md §2）。
 *
 * | 开关 | 落地方式 |
 * | --- | --- |
 * | 关 offload | 不设置 `setAudioOffloadSupportProvider`，也不调用 `setOffloadMode(ENABLED)` |
 * | 关 float 输出 | `DefaultAudioSink.Builder#setEnableFloatOutput(false)` + factory 同名字段 |
 * | 倍速走 Sonic | `setEnableAudioTrackPlaybackParams(false)`（AudioTrack 层不改速） |
 * | 音频缓冲 ≥400ms | `DefaultAudioTrackBufferSizeProvider.Builder#setMinPcmBufferDurationUs(400_000)` |
 * | 解码回落 | `setEnableDecoderFallback(true)` |
 * | 不引入 FFmpeg | `EXTENSION_RENDERER_MODE_OFF` |
 */

/**
 * 已知会让固件解码器直接 abort 的 codec 黑名单。
 *
 * 实测：MStar 平台 `OMX.MS.MP3.Decoder` 在播「带 MP3 音轨的直播源」时会触发
 * `ACodec.cpp:3533` 断言并直接 abort 进程（App 闪退，不是 Exception 能 catch 的）。
 * 这类 codec 必须从选择列表里剔除，让 ExoPlayer 回落到平台软件解码器
 * （c2.android.mp3.decoder 等），宁可多一次 CPU 解码也不能崩。
 */
@UnstableApi
object SafeCodecSelector : androidx.media3.exoplayer.mediacodec.MediaCodecSelector {

    private val delegate = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT

    /** 只在 MStar/MS 系固件上生效，其它平台保持默认（避免误伤硬件解码）。 */
    private val blockedPrefixes = listOf("OMX.MS.MP3", "OMX.MS.AMR")

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
        val all = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        val filtered = all.filterNot { info -> blockedPrefixes.any { info.name.startsWith(it) } }
        if (filtered.isEmpty()) return all   // 唯一解码器被剔除时宁可试一次，也不让整条源直接失败
        return filtered
    }
}

@UnstableApi
class TvRenderersFactory(
    context: Context,
    private val onAudioSinkCreated: (CalibrationAudioSink) -> Unit,
) : DefaultRenderersFactory(context) {

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
        // t25：codecReuse 开关打开时不再做 decoder fallback 探测（多一次失败 codec init = 白花 60ms）
        setEnableDecoderFallback(!PlayerTuning.codecReuseEnabled)
        setEnableAudioFloatOutput(false)
        setEnableAudioTrackPlaybackParams(false)
        // 固件解码器黑名单：避免 OMX.MS.MP3 触发 ACodec abort（见 SafeCodecSelector 注释）
        setMediaCodecSelector(SafeCodecSelector)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val bufferSizeProvider = DefaultAudioTrackBufferSizeProvider.Builder()
            .setMinPcmBufferDurationUs(PlayerTuning.AUDIO_BUFFER_MIN_US)
            .setMaxPcmBufferDurationUs(PlayerTuning.AUDIO_BUFFER_MAX_US)
            .setPcmBufferMultiplicationFactor(PlayerTuning.AUDIO_BUFFER_MULTIPLIER)
            .build()

        val sink = DefaultAudioSink.Builder(context)
            // 老设备 float 路径会多一次重采样，固定 16-bit PCM
            .setEnableFloatOutput(false)
            // 倍速交给 Sonic（默认 AudioProcessor 链），AudioTrack playbackParams 会与视频时钟脱钩
            .setEnableAudioTrackPlaybackParams(false)
            .setAudioTrackBufferSizeProvider(bufferSizeProvider)
            // 关键：不设置 AudioOffloadSupportProvider / 不启用 offload，走纯 PCM 时钟
            .build()

        val wrapper = CalibrationAudioSink(sink)
        onAudioSinkCreated(wrapper)
        TvLog.i(
            TAG,
            "audio sink: pcm16, sonic=${!enableAudioTrackPlaybackParams}, " +
                "buffer>=${PlayerTuning.AUDIO_BUFFER_MIN_US / 1000}ms, offload=off",
        )
        return wrapper
    }

    private companion object {
        const val TAG = "Renderers"
    }
}
