package com.xingmu.tv.player

import com.xingmu.tv.data.MediaUrl
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放器契约（t9 实现，t4 只调用）。
 *
 * 实现硬约束见 docs/PLAYBACK_SPEC.md 与 docs/AVSYNC_SPEC.md：
 * 音频主时钟、关 offload、Sonic 倍速、SurfaceView 直出、切集/换源必须 stop→setMediaItem→prepare。
 */
interface PlayerController {

    sealed interface State {
        data object Idle : State
        data object Buffering : State
        data object Ready : State
        data object Playing : State
        data object Paused : State
        data object Ended : State
        data class Error(val code: String, val message: String) : State
    }

    val state: StateFlow<State>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>

    /** 加载并播放一条媒体（切集/换源都走它，内部负责 stop→setMediaItem→prepare） */
    fun play(media: MediaUrl, startPositionMs: Long = 0L)

    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun setQuality(maxHeight: Int)   // 1080 / 720 / 480；0 = auto
    fun setAudioDelayMs(delayMs: Int) // 音画校准 −500..+500
    fun release()
}
