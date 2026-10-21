package com.xingmu.tv.ui.player

import com.xingmu.tv.data.Episode

/** 播放页入参：从详情页/继续观看进入，UI 壳只依赖这个纯数据对象。 */
data class PlaybackRequest(
    val vodId: String,
    val sourceId: String,
    val vodName: String,
    val year: String = "",
    val playFrom: String,
    val episodeIndex: Int,
    val episodes: List<Episode>,
    val startPositionMs: Long = 0L,
) {
    fun episodeAt(index: Int): Episode? = episodes.firstOrNull { it.index == index }

    val currentEpisode: Episode? get() = episodeAt(episodeIndex)
}
