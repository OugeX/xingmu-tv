package com.tvbox.app.player

import android.content.Context
import com.tvbox.app.core.AppScopes
import com.tvbox.app.core.TvLog
import com.tvbox.app.data.VodDetail
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.LinkedHashSet

/**
 * 详情页 VOD 预热（t22）：用户在详情页停留时，后台把第一集 m3u8 playlist + 首片前缀写入
 * 与播放器共用的 Media3 SimpleCache，按播放键时直接命中。
 *
 * 触发点放在数据层 `getDetail` 成功之后（详情页一打开就等 300ms 再预取），
 * 这样不依赖 UI 焦点实现，也不改布局。
 */
object VodPrefetch {

    private const val TAG = "VodPrefetch"

    private val requested = Collections.synchronizedSet(LinkedHashSet<String>())

    fun prefetchDetail(context: Context, detail: VodDetail) {
        val from = detail.playFroms.firstOrNull() ?: return
        val episode = detail.episodesByFrom[from]?.firstOrNull() ?: return
        prefetchEpisode(context, episode.url)
    }

    fun prefetchEpisode(context: Context, url: String) {
        if (url.isBlank()) return
        if (!requested.add(url)) return
        AppScopes.io.launch {
            delay(300) // 详情页停留 ≥300ms 才预取，避免快速划过浪费带宽
            TvLog.i(TAG, "prefetch VOD first episode: ${url.take(100)}")
            PlayerHttp.prewarm(context.applicationContext, url, fetchSegment = true, fromStart = true)
        }
    }
}
