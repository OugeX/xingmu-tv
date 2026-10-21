package com.tvbox.app.source

import com.tvbox.app.data.SourceConfig
import com.tvbox.app.data.SourceKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * 导入后的内存表示（tvapp.source.v1.1 全字段）。bundle 在导入时被展开成多条 [ParsedSource]。
 *
 * 为什么需要它：`data/Models.kt` 的 [SourceConfig] 是给 UI/播放用的投影（没有 m3u 的 url/inline、
 * schema 版本、EPG、note 等字段），而持久化必须保留完整配置（PLAN_SOURCES §12 config_json）。
 */
data class ParsedSource(
    val schema: String,
    val kind: SourceKind,
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 100,
    val note: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Int = 6000,
    val maxConcurrent: Int = 2,
    // ---- maccms ----
    val baseUrl: String = "",
    val flavor: String = "macjson",
    val apiPathMode: String = "query",
    val fallbackBaseUrls: List<String> = emptyList(),
    val pageSize: Int = 20,
    val categories: Map<String, List<Int>> = emptyMap(),
    val classTtlMin: Int = 1440,
    // ---- m3u ----
    val m3uUrl: String? = null,
    val inline: String? = null,
    val refreshIntervalMin: Int = 360,
    val maxBytes: Int = 2 * 1024 * 1024,
    val filters: M3uFilters = M3uFilters(),
    val dedupe: M3uDedupe = M3uDedupe(),
    val channelDefs: List<ChannelDef> = emptyList(),
    // ---- v1.1 公共 ----
    val epgUrls: List<String> = emptyList(),
    val adBlock: AdBlock = AdBlock(),
    val skipIntroSeconds: Int = 0,
    val skipIntroCue: Boolean = false,
    val maxQuality: String = "auto",
    val preferredCodec: String = "auto",
    /** 规范化后的完整 JSON（持久化到 source.config_json），保留未知字段以便未来迁移。 */
    val raw: JSONObject,
    val bundleId: String? = null,
) {
    /** 端点（maccms 用 baseUrl，m3u 用订阅地址，inline 源无端点）。 */
    val endpoint: String get() = if (kind == SourceKind.MACCMS) baseUrl else (m3uUrl ?: "")

    /**
     * 端点指纹：maccms/m3u-url 走规范化 URL；**inline m3u 用内容哈希**，
     * 否则两条 inline 源指纹都为空会撞 UNIQUE(kind,fingerprint) 索引。
     */
    fun fingerprint(): String {
        val ep = endpoint
        if (ep.isNotEmpty()) return UrlRules.fingerprint(kind.name.lowercase(), ep)
        val content = inline ?: return ""
        return kind.name.lowercase() + "|inline:" + contentHash(content)
    }

    private fun contentHash(text: String): String = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        digest.take(8).joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        Integer.toHexString(text.hashCode())
    }

    /** 投影到冻结契约 [SourceConfig]（m3u 源 baseUrl 放订阅地址，inline 源为空串）。 */
    fun toSourceConfig(): SourceConfig = SourceConfig(
        id = id,
        name = name,
        kind = kind,
        baseUrl = endpoint,
        fallbackBaseUrls = fallbackBaseUrls,
        priority = priority,
        enabled = enabled,
        headers = headers,
        timeoutMs = timeoutMs,
        pageSize = pageSize,
        categories = categories,
        adBlockDomains = adBlock.domains,
        adBlockKeywords = adBlock.urlKeywords,
        skipIntroSeconds = skipIntroSeconds,
        maxQuality = maxQuality,
    )

    fun toJson(): JSONObject = raw
}

data class M3uFilters(
    val includeGroups: List<String> = emptyList(),
    val excludeGroups: List<String> = emptyList(),
    val excludeNameRegex: String? = null,
)

data class M3uDedupe(
    val byTvgId: Boolean = true,
    val byName: Boolean = true,
    val mergeUrls: Boolean = true,
)

data class ChannelDef(
    val name: String,
    val urls: List<String>,
    val group: String? = null,
    val logo: String? = null,
    val tvgId: String? = null,
    val epgId: String? = null,
    val desc: String? = null,
)

data class AdBlock(
    val enabled: Boolean = true,
    val domains: List<String> = emptyList(),
    val urlKeywords: List<String> = emptyList(),
)

/** 展开后的 bundle 元信息（子源各自带 bundleId）。 */
data class ParsedBundle(
    val id: String,
    val name: String,
    val version: Int,
    val updatedAt: String?,
    val refreshIntervalHour: Int,
    val merge: String,
    val signature: String?,
    val sources: List<ParsedSource>,
)

/** 广告规则匹配（预编译在调用方做，纯函数便于单测；与 validate_sources.py 同规则）。 */
object AdBlockRules {

    fun domainRuleMatches(host: String, rule: String): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        val r = rule.trim().lowercase().trimEnd('.')
        if (h.isEmpty() || r.isEmpty()) return false
        if (r.startsWith("*.")) {
            val base = r.substring(2)
            if (base.isEmpty()) return false
            return h == base || h.endsWith(".$base")
        }
        return h == r || h.endsWith(".$r")
    }

    fun matchDomain(host: String, rules: List<String>): String? =
        rules.firstOrNull { it.isNotBlank() && domainRuleMatches(host, it) }

    /** path+query 子串匹配，大小写不敏感；返回命中的关键字。 */
    fun matchKeyword(url: String, keywords: List<String>): String? {
        if (keywords.isEmpty()) return null
        val target = url.substringAfter("://", url).substringAfter('/', "")
        val lower = target.lowercase()
        return keywords.firstOrNull { it.isNotBlank() && lower.contains(it.lowercase()) }
    }

    /** 规则集合是否会把自身接口/EPG 拦掉（导入期自伤检查，E_ADBLOCK）。 */
    fun selfHarm(domains: List<String>, protectedUrls: List<String>): Pair<String, String>? {
        for (u in protectedUrls) {
            if (!UrlRules.isHttpUrl(u)) continue
            val host = UrlRules.hostOf(u)
            val hit = matchDomain(host, domains) ?: continue
            return host to hit
        }
        return null
    }
}

/** 把 SourceConfig 的 categories 键（字符串）对齐到枚举名常量，避免拼写漂移。 */
object CategoryKeys {
    val ALL = listOf("MOVIE", "TV", "ANIME", "VARIETY", "SHORT", "SPORTS", "OTHER")

    /** 工程内约定：list(typeId) 的 1..7 对应上表顺序（与 PLAN_SOURCES §2 MediaCategory 一致）。 */
    fun fromOrdinal(typeId: Int): String? = ALL.getOrNull(typeId - 1)
}

/** 便捷：JSONArray -> List<String>（非字符串元素跳过）。 */
internal fun JSONArray.toStringList(): List<String> {
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        val v = opt(i)
        if (v is String) out.add(v)
    }
    return out
}
