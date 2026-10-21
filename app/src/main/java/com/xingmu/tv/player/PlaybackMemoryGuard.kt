package com.xingmu.tv.player

import android.content.Context
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.data.AppImages

/**
 * t25 / PERF_ROADMAP P1-7：播放态资源让位。
 *
 * 进入播放页/直播页时：
 *  1. Coil 内存缓存软上限压到 8MB，并立刻裁掉超出部分（详情页 1080p 大图最先被释放）；
 *  2. [pauseImages] 置 true，列表海报不再发起新请求（PosterImage 读这个开关）；
 *  3. 退出播放恢复 24MB 上限 + 放开图片请求（缓存本身会自然回填，不做“恢复旧位图”这种假动作）。
 *
 * 计数用 depth：直播页和播放页切换时可能重叠 enter/exit，只有最后一个 exit 才真正恢复。
 */
object PlaybackMemoryGuard {

    private const val TAG = "MemGuard"
    const val PLAYBACK_CAP_BYTES = 8 * 1024 * 1024

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var pauseImages: Boolean = false
        private set

    private var depth = 0

    fun enter(context: Context?) {
        synchronized(this) {
            depth += 1
            if (active) return
            active = true
            pauseImages = true
        }
        val before = imageCacheSize(context)
        AppImages.setMemoryBudget(PLAYBACK_CAP_BYTES)
        val after = imageCacheSize(context)
        TvLog.i(TAG, "playback enter: coil cache ${before / 1024}KB -> ${after / 1024}KB (cap 8MB), image requests paused")
    }

    fun exit() {
        synchronized(this) {
            depth = (depth - 1).coerceAtLeast(0)
            if (depth > 0 || !active) return
            active = false
            pauseImages = false
        }
        AppImages.setMemoryBudget(Int.MAX_VALUE)
        TvLog.i(TAG, "playback exit: coil cache cap restored (24MB), image requests resumed")
    }


    /**
     * 导航兜底：离开播放/直播/短视频页面时强制回到空闲态。
     *
     * 修复真机问题：enter()/exit() 依赖页面 DisposableEffect，若页面重建导致 enter 多于 exit，
     * depth 会永久 >0 → pauseImages 永远为 true → 详情页/列表封面全部不加载（用户实测症状）。
     */
    fun ensureIdle(reason: String) {
        val wasActive: Boolean
        synchronized(this) {
            wasActive = active || depth != 0
            depth = 0
            active = false
            pauseImages = false
        }
        if (wasActive) {
            AppImages.setMemoryBudget(Int.MAX_VALUE)
            TvLog.w(TAG, "playback guard force-idle ($reason), image requests resumed")
        }
    }

    /** 兜底：页面异常重建时把计数清零。 */
    fun reset() {
        synchronized(this) {
            depth = 0
            active = false
            pauseImages = false
        }
        AppImages.setMemoryBudget(Int.MAX_VALUE)
    }

    private fun imageCacheSize(context: Context?): Int = runCatching {
        context?.let { AppImages.loader(it.applicationContext).memoryCache?.size } ?: 0
    }.getOrDefault(0)
}
