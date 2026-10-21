package com.xingmu.tv.data

/**
 * 跨实现线共享的数据模型（tvapp.source.v1.1 映射，见 docs/PLAN_SOURCES.md §2.1）。
 *
 * 归属：engineer 维护；t9（播放/直播）与 t10（导入）只消费，不改字段语义。
 */

/** 源类型 */
enum class SourceKind { MACCMS, M3U, BUNDLE }

/** 一条可播放地址（线路/分集） */
data class MediaUrl(
    val label: String,          // 如 "第01集" / "线路1"
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/** 分集（点播） */
@androidx.compose.runtime.Immutable
data class Episode(
    val index: Int,             // 1-based
    val title: String,
    val url: String,
    val playFrom: String,       // vod_play_from 中的线路名，如 bfzym3u8
)

/** 列表页作品摘要（@Immutable：t28 列表滚动时跳过整表重组） */
@androidx.compose.runtime.Immutable
data class VodSummary(
    val vodId: String,
    val sourceId: String,
    val name: String,
    val pic: String,
    val remarks: String,        // 如 "更新至10集" / "HD"
    val year: String,
    val typeId: String,
    val typeName: String,
    val score: String,
    val sourceCount: Int = 1,   // 跨源聚合时的可用源数
    /** t28：MacCMS 的 vod_pic_thumb（缩略图）；为空时回退 [pic] 并降半尺寸请求 */
    val picThumb: String = "",
)

/** 详情页作品 */
data class VodDetail(
    val vodId: String,
    val sourceId: String,
    val name: String,
    val pic: String,
    val slidePic: String,
    val score: String,
    val year: String,
    val area: String,
    val lang: String,
    val duration: String,
    val remarks: String,
    val actors: String,
    val director: String,
    val content: String,
    val typeName: String,
    val playFroms: List<String>,        // vod_play_from 按 $$$ 拆分
    val episodesByFrom: Map<String, List<Episode>>,
)

/** 直播频道（t9 消费；字段对齐 assets/live_seed.json 与 PLAN_SOURCES §9.6） */
@androidx.compose.runtime.Immutable
data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val desc: String?,
    val urls: List<ChannelUrl>,
)

@androidx.compose.runtime.Immutable
data class ChannelUrl(
    val url: String,
    val quality: String?,
    val resolution: String?,
    val latencyMs: Int?,
    val fallbackOnly: Boolean = false,
)

/** 源配置（一次性解析自 assets/sources.builtin.json / 用户导入） */
data class SourceConfig(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val baseUrl: String = "",
    val fallbackBaseUrls: List<String> = emptyList(),
    val priority: Int = 100,
    val enabled: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Int = 6000,
    val pageSize: Int = 20,
    val categories: Map<String, List<Int>> = emptyMap(),
    val adBlockDomains: List<String> = emptyList(),
    val adBlockKeywords: List<String> = emptyList(),
    val skipIntroSeconds: Int = 0,
    val maxQuality: String = "auto",
)
