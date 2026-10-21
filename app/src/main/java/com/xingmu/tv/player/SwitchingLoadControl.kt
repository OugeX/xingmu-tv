package com.xingmu.tv.player

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.xingmu.tv.core.TvLog

/**
 * 点播/直播两套 `DefaultLoadControl`，运行期在两者间切换而**不重建播放器**。
 *
 * 为什么不直接用两个 ExoPlayer：1GB 盒子只允许一个 MediaCodec 实例（PLAN_ARCHITECTURE §7.6）。
 * 因此 ExoPlayer.Builder 只接收本包装器，[setLive] / [raiseBuffer] 只改委托对象。
 *
 * 关键参数见 [BufferProfile]；共享同一个 [DefaultAllocator] 防止切换时 allocator 被换掉。
 */
@UnstableApi
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class SwitchingLoadControl(initialLive: Boolean = false) : LoadControl {

    private val allocator = DefaultAllocator(true, 64 * 1024)

    private val controls: Map<BufferProfile, DefaultLoadControl> = BufferProfile.entries.associateWith { profile ->
        DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                profile.minBufferMs,
                profile.maxBufferMs,
                profile.bufferForPlaybackMs,
                profile.bufferForPlaybackAfterRebufferMs,
            )
            .setTargetBufferBytes(profile.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false) // 1GB 机器以内存上限为准
            .setBackBuffer(profile.backBufferMs, profile.backBufferMs > 0)
            .build()
    }

    @Volatile
    private var live: Boolean = initialLive

    @Volatile
    private var safe: Boolean = false

    // 切档后把最近一次轨道选择重放到新委托，否则 DefaultLoadControl 的 targetBufferSize 仍是 0，
    // shouldContinueLoading 会立刻返回 false（表现为换挡后不再加载/卡住）。
    // 注意：新委托必须先 onPrepared(playerId) 建立 loadingState，否则 onTracksSelected 会 NPE。
    private var lastPlayerId: androidx.media3.exoplayer.analytics.PlayerId? = null
    private var lastParameters: LoadControl.Parameters? = null
    private var lastTrackGroups: androidx.media3.exoplayer.source.TrackGroupArray? = null
    private var lastSelections: Array<androidx.media3.exoplayer.trackselection.ExoTrackSelection?>? = null

    private val profile: BufferProfile
        get() = when {
            live && safe -> BufferProfile.LIVE_SAFE
            live -> BufferProfile.LIVE
            safe -> BufferProfile.VOD_SAFE
            else -> BufferProfile.VOD
        }

    private val delegate: DefaultLoadControl
        get() = controls.getValue(profile)

    fun setLive(isLive: Boolean) {
        if (live == isLive) return
        live = isLive
        // 新起播会在 onTracksSelected 时自然落到新委托；这里不重放（避免旧 mediaPeriodId 失效）
        TvLog.i(TAG, "loadControl profile=${profile.name} (live=$isLive safe=$safe)")
    }

    /** AVSYNC_SPEC §4：欠载后“提缓冲一档”。 */
    fun raiseBuffer() {
        if (safe) return
        safe = true
        reapplyTracks()
        TvLog.w(TAG, "loadControl raiseBuffer -> ${profile.name}")
    }

    fun isSafeBuffer(): Boolean = safe

    fun reset() {
        safe = false
        // 新起播会重新触发 onTracksSelected，不做旧参数重放
    }

    private fun reapplyTracks() {
        val parameters = lastParameters ?: return
        val groups = lastTrackGroups ?: return
        val selections = lastSelections ?: return
        val playerId = lastPlayerId ?: parameters.playerId
        runCatching {
            delegate.onPrepared(playerId)
            delegate.onTracksSelected(parameters, groups, selections)
        }.onFailure {
            TvLog.w(TAG, "reapply tracks failed: ${it.message}")
            lastParameters = null
        }
    }

    override fun getAllocator(): Allocator = allocator

    // Media3 1.5.1：ExoPlayer 只调用带 PlayerId / Parameters 的新重载；
    // 旧的无参重载在接口默认实现里直接 throw（1.5.1 已移除兼容），绝不能委托过去。
    override fun getBackBufferDurationUs(playerId: androidx.media3.exoplayer.analytics.PlayerId): Long =
        delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: androidx.media3.exoplayer.analytics.PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(loadingInfo: LoadControl.Parameters): Boolean =
        delegate.shouldContinueLoading(loadingInfo)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        timeline: androidx.media3.common.Timeline,
        mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    override fun onPrepared(playerId: androidx.media3.exoplayer.analytics.PlayerId) {
        lastPlayerId = playerId
        delegate.onPrepared(playerId)
    }

    override fun onStopped(playerId: androidx.media3.exoplayer.analytics.PlayerId) =
        delegate.onStopped(playerId)

    override fun onReleased(playerId: androidx.media3.exoplayer.analytics.PlayerId) =
        delegate.onReleased(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: androidx.media3.exoplayer.source.TrackGroupArray,
        trackSelections: Array<androidx.media3.exoplayer.trackselection.ExoTrackSelection?>,
    ) {
        lastParameters = parameters
        lastTrackGroups = trackGroups
        lastSelections = trackSelections
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    private companion object {
        const val TAG = "LoadControl"
    }
}
