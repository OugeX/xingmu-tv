package com.xingmu.tv.data.casual

import androidx.compose.runtime.Immutable
import com.xingmu.tv.core.Result
import com.xingmu.tv.data.MediaUrl

/** 电台（Radio Browser / 内置精选）。 */
@Immutable
data class RadioStation(
    val id: String,
    val name: String,
    val tag: String,
    val codec: String,
    val bitrateKbps: Int,
    val urls: List<String>,
    val logo: String? = null,
    val group: String = "",
)

/** B 站短视频（榜单/热门条目）。 */
@Immutable
data class ShortClip(
    val bvid: String,
    val title: String,
    val pic: String,
    val durationSec: Int,
    val owner: String,
    val views: Long,
    val aid: Long = 0L,
)

/** 看页分段（rid 对应 B 站分区；推荐走 popular 接口）。 */
enum class CasualTab(val rid: Int, val label: String) {
    RECOMMEND(0, "推荐"),
    MUSIC(3, "音乐"),
    ANIME(1, "动画"),
    GAME(4, "游戏"),
    KNOWLEDGE(36, "知识"),
    LIFE(160, "生活"),
}

/**
 * 随便听听看看仓库（docs/CASUAL_SPEC.md §3）。
 * 缓存：电台 6h、榜单 10min；连续 3 次失败熔断 10 分钟。
 */
interface CasualRepository {
    suspend fun radios(page: Int = 1): Result<List<RadioStation>>
    suspend fun clips(tab: CasualTab, page: Int = 1): Result<List<ShortClip>>
    suspend fun resolveClip(bvid: String): Result<MediaUrl>
}
