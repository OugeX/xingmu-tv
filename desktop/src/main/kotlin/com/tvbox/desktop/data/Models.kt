package com.tvbox.desktop.data

import org.json.JSONObject

/** 与 Android 端 app/src/main/java/com/tvbox/app/data/Models.kt 字段语义保持一致。 */

enum class SourceKind { MACCMS, M3U, BUNDLE }

data class SourceIssue(val code: String, val path: String, val message: String) {
    override fun toString(): String = "$code $path: $message"
}

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
    val preferredCodec: String = "auto",
    val m3uUrl: String? = null,
    val m3uInline: String? = null,
) {
    val displayEndpoint: String
        get() = when (kind) {
            SourceKind.MACCMS -> baseUrl
            SourceKind.M3U -> m3uUrl ?: "内嵌 m3u（${m3uInline?.length ?: 0} 字符）"
            SourceKind.BUNDLE -> "合集"
        }
}

/** 导入/校验后的源，保留原始 JSON 便于原样落盘与合并。 */
data class ImportedSource(val config: SourceConfig, val raw: JSONObject)

data class VodRef(val sourceId: String, val sourceName: String, val vodId: String)

/** MacCMS `ac=list` 返回的分类节点（type_pid=0 为一级分类）。 */
data class MacCmsClass(val typeId: Int, val typeName: String, val typePid: Int)

data class VodSummary(
    val vodId: String,
    val sourceId: String,
    val sourceName: String,
    val name: String,
    val pic: String,
    val remarks: String,
    val year: String,
    val typeId: String,
    val typeName: String,
    val score: String,
    val refs: List<VodRef> = emptyList(),
) {
    val sourceCount: Int get() = refs.size.coerceAtLeast(1)
}

data class Episode(
    val index: Int,
    val title: String,
    val url: String,
    val playFrom: String,
)

data class VodDetail(
    val vodId: String,
    val sourceId: String,
    val sourceName: String,
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
    val playFroms: List<String>,
    val episodesByFrom: Map<String, List<Episode>>,
) {
    val firstEpisode: Episode? get() = episodesByFrom.values.firstOrNull()?.firstOrNull()
}

data class LiveLine(
    val url: String,
    val quality: String? = null,
    val tier: String? = null,
    val fallbackOnly: Boolean = false,
    val latencyMs: Int? = null,
)

data class LiveChannel(
    val id: String,
    val name: String,
    val group: String,
    val groupId: String,
    val number: Int,
    val tvgId: String? = null,
    val logo: String? = null,
    val desc: String? = null,
    val status: String = "unknown",
    val quality: String? = null,
    val lines: List<LiveLine> = emptyList(),
) {
    val bestLine: LiveLine? get() = lines.firstOrNull()
    val healthy: Boolean get() = status.equals("healthy", ignoreCase = true)
    val qualityLabel: String get() = quality ?: bestLine?.quality ?: "未知"
}

data class LiveGroup(val id: String, val name: String, val order: Int, val count: Int)

data class LiveSnapshot(
    val groups: List<LiveGroup>,
    val channels: List<LiveChannel>,
    val generatedAt: String?,
    val source: String,
    val note: String? = null,
)

/** 一次播放请求：起点 + 备用线路（点播用来换线路/降清晰度）。 */
data class PlayLine(
    val label: String,
    val url: String,
    val quality: String? = null,
)

data class PlaybackRequest(
    val title: String,
    val subtitle: String,
    val isLive: Boolean,
    val lines: List<PlayLine>,
    val headers: Map<String, String> = emptyMap(),
    val sourceId: String = "",
)
