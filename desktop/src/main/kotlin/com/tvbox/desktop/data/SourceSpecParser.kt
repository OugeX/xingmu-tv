package com.tvbox.desktop.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/**
 * `tvapp.source.v1 / v1.1` 解析 + 校验。
 *
 * 错误码与 `tools/validate_sources.py` 对齐：
 *   E_JSON / E_SCHEMA / E_ID / E_URL / E_FIELD / E_EPG / E_ADBLOCK / E_CHANNEL / E_REGEX / E_NESTED
 * 桌面端额外增加网络侧错误 E_NETWORK（导入 URL 时），仅用于 UI 提示。
 *
 * 校验分两层：L1 字段/类型/枚举（对应 sources.schema.json 的常用约束），
 * L2 语义（id/端点唯一、url 与 inline 二选一、分类键、广告规则自伤、频道重复等）。
 */
object SourceSpecParser {

    data class ImportReport(
        val doc: JSONObject?,
        val sources: List<ImportedSource>,
        val issues: List<SourceIssue>,
    ) {
        val ok: Boolean get() = issues.isEmpty() && sources.isNotEmpty()
        val firstError: String? get() = issues.firstOrNull()?.let { "${it.code}: ${it.message}" }
    }

    private val SCHEMAS = setOf("tvapp.source.v1", "tvapp.source.v1.1")
    private val KINDS = setOf("maccms", "m3u", "bundle")
    private val ID_RE = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private val HEADER_NAME_RE = Regex("^(User-Agent|Referer|Origin|Cookie|Authorization|Accept|Accept-Language|X-[A-Za-z0-9-]+)$")
    private val CATEGORY_KEYS = setOf("MOVIE", "TV", "ANIME", "VARIETY", "SHORT", "SPORTS", "OTHER")
    private val QUALITY_VALUES = setOf("auto", "1080p", "720p", "480p")
    private val CODEC_VALUES = setOf("h264", "hevc", "auto")
    private val MERGE_VALUES = setOf("byId", "replace")
    private val DOMAIN_RE = Regex("^(\\*\\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    private val SIGNATURE_RE = Regex("^sha256:[0-9a-f]{64}$")
    private val DATE_TIME_RE = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$")

    private val TOP_KEYS = setOf(
        "schema", "kind", "id", "name", "enabled", "priority", "note", "headers", "timeoutMs",
        "maxConcurrent", "epgUrls", "adBlock", "skipIntroSeconds", "skipIntroCue", "maxQuality",
        "preferredCodec", "baseUrl", "flavor", "apiPathMode", "fallbackBaseUrls", "pageSize",
        "categories", "classTtlMin", "url", "inline", "refreshIntervalMin", "maxBytes", "filters",
        "dedupe", "channels", "version", "updatedAt", "refreshIntervalHour", "signature", "merge", "sources",
    )
    private val M3U_FILTER_KEYS = setOf("includeGroups", "excludeGroups", "excludeNameRegex")
    private val M3U_DEDUPE_KEYS = setOf("byTvgId", "byName", "mergeUrls")
    private val CHANNEL_KEYS = setOf("name", "url", "urls", "group", "logo", "tvgId", "epgId", "desc")

    private const val MAX_EPG_URLS = 3
    private const val MAX_AD_DOMAINS = 50
    private const val MAX_AD_KEYWORDS = 100
    private const val MAX_CHANNELS = 5000
    private const val MAX_CHANNEL_URLS = 8
    private const val MAX_DESC_LEN = 200
    private const val MAX_SKIP_INTRO = 600

    fun parseText(text: String, origin: String = "input"): ImportReport {
        if (text.isBlank()) {
            return ImportReport(null, emptyList(), listOf(SourceIssue("E_JSON", origin, "内容为空")))
        }
        return try {
            parseDoc(JSONObject(text))
        } catch (t: JSONException) {
            ImportReport(null, emptyList(), listOf(SourceIssue("E_JSON", origin, "JSON 解析失败：${t.message}")))
        }
    }

    fun parseDoc(doc: JSONObject): ImportReport {
        val issues = mutableListOf<SourceIssue>()
        val imported = mutableListOf<ImportedSource>()

        val isBundle = doc.optString("kind") == "bundle"
        if (isBundle) {
            validateCommon(doc, "bundle", issues)
            validateBundleMeta(doc, issues)
            val arr = doc.optJSONArray("sources")
            if (arr == null) {
                issue(issues, "E_SCHEMA", "sources", "bundle 缺少 sources 数组")
            } else {
                if (arr.length() !in 1..50) {
                    issue(issues, "E_SCHEMA", "sources", "bundle.sources 数量必须在 1..50")
                }
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj == null) {
                        issue(issues, "E_SCHEMA", "sources[$i]", "源必须是对象")
                        continue
                    }
                    val where = "#$i(${obj.optString("id", "?")})"
                    if (obj.optString("kind") == "bundle") {
                        issue(issues, "E_NESTED", where, "不允许嵌套 bundle")
                        continue
                    }
                    val before = issues.size
                    val config = validateSource(obj, where, issues)
                    if (config != null && issues.size == before) {
                        imported += ImportedSource(config, obj)
                    }
                }
            }
        } else {
            val where = "#0(${doc.optString("id", "?")})"
            val config = validateSource(doc, where, issues)
            if (config != null) imported += ImportedSource(config, doc)
        }

        semanticChecks(imported, issues)
        return ImportReport(doc, imported, issues)
    }

    // ------------------------------------------------------------------
    // 公共字段
    // ------------------------------------------------------------------

    private fun validateCommon(o: JSONObject, where: String, issues: MutableList<SourceIssue>) {
        val unknown = o.keys().asSequence().filterNot { it in TOP_KEYS }.toList()
        if (unknown.isNotEmpty()) issue(issues, "E_SCHEMA", where, "未知字段：${unknown.joinToString(",")}")

        val schema = o.optString("schema")
        if (schema !in SCHEMAS) issue(issues, "E_SCHEMA", "$where.schema", "schema 必须是 tvapp.source.v1 / v1.1（当前：$schema）")

        val kind = o.optString("kind")
        if (kind !in KINDS) issue(issues, "E_SCHEMA", "$where.kind", "kind 非法：$kind")

        val id = o.optString("id")
        if (!ID_RE.matches(id)) issue(issues, "E_SCHEMA", "$where.id", "id 必须匹配 ^[a-z0-9][a-z0-9._-]{1,63}$（当前：$id）")

        val name = o.optString("name")
        if (name.length !in 1..40) issue(issues, "E_SCHEMA", "$where.name", "name 长度必须在 1..40")

        checkType(o, "enabled", Boolean::class.java, issues, where)
        checkType(o, "priority", Int::class.java, issues, where) { v ->
            val priority = (v as Number).toInt()
            if (priority !in 0..1000) issue(issues, "E_SCHEMA", "$where.priority", "priority 必须在 0..1000")
        }
        val note = o.opt("note")
        if (note != null && note != JSONObject.NULL && note !is String) {
            issue(issues, "E_SCHEMA", "$where.note", "note 必须是字符串")
        } else if (note is String && note.length > 200) {
            issue(issues, "E_SCHEMA", "$where.note", "note 最长 200 字")
        }

        validateHeaders(o, where, issues)

        val timeout = o.optInt("timeoutMs", 6000)
        if (o.has("timeoutMs") && timeout !in 1_000..15_000) {
            issue(issues, "E_SCHEMA", "$where.timeoutMs", "timeoutMs 必须在 1000..15000")
        }
        val concurrent = o.optInt("maxConcurrent", 2)
        if (o.has("maxConcurrent") && concurrent !in 1..4) {
            issue(issues, "E_SCHEMA", "$where.maxConcurrent", "maxConcurrent 必须在 1..4")
        }

        val skip = o.optInt("skipIntroSeconds", 0)
        if (skip !in 0..MAX_SKIP_INTRO) issue(issues, "E_FIELD", "$where.skipIntroSeconds", "必须在 0..$MAX_SKIP_INTRO")
        checkType(o, "skipIntroCue", Boolean::class.java, issues, where)
        val maxQuality = o.optString("maxQuality", "auto")
        if (maxQuality !in QUALITY_VALUES) issue(issues, "E_FIELD", "$where.maxQuality", "非法：$maxQuality")
        val codec = o.optString("preferredCodec", "auto")
        if (codec !in CODEC_VALUES) issue(issues, "E_FIELD", "$where.preferredCodec", "非法：$codec")

        validateEpg(o, where, issues)
        validateAdBlock(o, where, issues)
    }

    private fun validateHeaders(o: JSONObject, where: String, issues: MutableList<SourceIssue>) {
        if (!o.has("headers")) return
        val headers = o.optJSONObject("headers")
        if (headers == null) {
            issue(issues, "E_SCHEMA", "$where.headers", "headers 必须是对象")
            return
        }
        val keys = headers.keys().asSequence().toList()
        if (keys.size > 8) issue(issues, "E_SCHEMA", "$where.headers", "headers 最多 8 项")
        for (key in keys) {
            if (!HEADER_NAME_RE.matches(key)) issue(issues, "E_SCHEMA", "$where.headers", "非法 header 名：$key")
            val value = headers.opt(key)
            if (value !is String) {
                issue(issues, "E_SCHEMA", "$where.headers.$key", "header 值必须是字符串")
                continue
            }
            if (value.length > 512) issue(issues, "E_SCHEMA", "$where.headers.$key", "header 值最长 512")
            if (value.contains('\r') || value.contains('\n')) issue(issues, "E_FIELD", "$where.headers.$key", "header 值含换行")
        }
    }

    private fun validateEpg(o: JSONObject, where: String, issues: MutableList<SourceIssue>) {
        if (!o.has("epgUrls")) return
        val epg = o.optJSONArray("epgUrls")
        if (epg == null) {
            issue(issues, "E_EPG", "$where.epgUrls", "必须是数组")
            return
        }
        if (epg.length() > MAX_EPG_URLS) issue(issues, "E_EPG", "$where.epgUrls", "最多 $MAX_EPG_URLS 项")
        for (i in 0 until epg.length()) {
            val url = epg.opt(i)
            if (url !is String || !isHttpUrl(url)) {
                issue(issues, "E_EPG", "$where.epgUrls[$i]", "EPG 地址必须是 http(s)")
            }
        }
    }

    private fun validateAdBlock(o: JSONObject, where: String, issues: MutableList<SourceIssue>) {
        if (!o.has("adBlock")) return
        val ab = o.optJSONObject("adBlock")
        if (ab == null) {
            issue(issues, "E_ADBLOCK", "$where.adBlock", "必须是对象")
            return
        }
        val unknown = ab.keys().asSequence().filterNot { it in setOf("enabled", "domains", "urlKeywords") }.toList()
        if (unknown.isNotEmpty()) issue(issues, "E_ADBLOCK", "$where.adBlock", "未知字段：${unknown.joinToString(",")}")
        val domains = ab.optJSONArray("domains")
        val keywords = ab.optJSONArray("urlKeywords")
        if (domains != null) {
            if (domains.length() > MAX_AD_DOMAINS) issue(issues, "E_ADBLOCK", "$where.adBlock.domains", "最多 $MAX_AD_DOMAINS 条")
            for (i in 0 until domains.length()) {
                val d = domains.opt(i)
                if (d !is String) {
                    issue(issues, "E_ADBLOCK", "$where.adBlock.domains[$i]", "域名必须是字符串")
                    continue
                }
                if (!DOMAIN_RE.matches(d)) issue(issues, "E_ADBLOCK", "$where.adBlock.domains[$i]", "域名格式非法：$d")
                if (d != d.lowercase()) issue(issues, "E_ADBLOCK", "$where.adBlock.domains[$i]", "域名必须小写：$d")
            }
        }
        if (keywords != null) {
            if (keywords.length() > MAX_AD_KEYWORDS) issue(issues, "E_ADBLOCK", "$where.adBlock.urlKeywords", "最多 $MAX_AD_KEYWORDS 条")
            for (i in 0 until keywords.length()) {
                val k = keywords.opt(i)
                if (k !is String) {
                    issue(issues, "E_ADBLOCK", "$where.adBlock.urlKeywords[$i]", "关键字必须是字符串")
                } else if (k.isBlank()) {
                    issue(issues, "E_ADBLOCK", "$where.adBlock.urlKeywords[$i]", "关键字不能为空白")
                } else if (k.trim() == "*") {
                    issue(issues, "E_ADBLOCK", "$where.adBlock.urlKeywords[$i]", "不允许 '*'（会拦截全部请求）")
                }
            }
        }

        // 自伤检查：API / 降级端点 / EPG 不能被自身广告规则拦截
        val domainRules = domains?.let { arr -> (0 until arr.length()).mapNotNull { arr.opt(it) as? String } }.orEmpty()
        val protected = buildList {
            o.optString("baseUrl").takeIf { it.isNotBlank() }?.let { add(it) }
            o.optString("url").takeIf { it.isNotBlank() }?.let { add(it) }
            o.optJSONArray("fallbackBaseUrls")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
            o.optJSONArray("epgUrls")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
        for (url in protected) {
            val host = hostOf(url)
            val hit = domainRules.firstOrNull { domainMatches(host, it) }
            if (hit != null) issue(issues, "E_ADBLOCK", where, "端点主机 $host 被自身规则 $hit 拦截")
        }
    }

    private fun validateBundleMeta(o: JSONObject, issues: MutableList<SourceIssue>) {
        val version = o.opt("version")
        if (version != null && version != JSONObject.NULL) {
            if (version !is Number || version.toInt() < 0) issue(issues, "E_SCHEMA", "bundle.version", "version 必须是非负整数")
        }
        val updatedAt = o.opt("updatedAt")
        if (updatedAt is String && updatedAt.isNotBlank() && !DATE_TIME_RE.matches(updatedAt)) {
            issue(issues, "E_SCHEMA", "bundle.updatedAt", "updatedAt 必须是 ISO date-time")
        }
        val interval = o.opt("refreshIntervalHour")
        if (interval != null && interval != JSONObject.NULL) {
            val v = (interval as? Number)?.toInt() ?: -1
            if (v !in 0..168) issue(issues, "E_SCHEMA", "bundle.refreshIntervalHour", "必须在 0..168")
        }
        val signature = o.opt("signature")
        if (signature is String && !SIGNATURE_RE.matches(signature)) {
            issue(issues, "E_SCHEMA", "bundle.signature", "signature 必须是 sha256:<64 hex>")
        }
        val merge = o.optString("merge", "byId")
        if (merge !in MERGE_VALUES) issue(issues, "E_SCHEMA", "bundle.merge", "merge 枚举非法：$merge")
    }

    // ------------------------------------------------------------------
    // 单源
    // ------------------------------------------------------------------

    private fun validateSource(o: JSONObject, where: String, issues: MutableList<SourceIssue>): SourceConfig? {
        validateCommon(o, where, issues)
        val kind = when (o.optString("kind")) {
            "maccms" -> SourceKind.MACCMS
            "m3u" -> SourceKind.M3U
            "bundle" -> SourceKind.BUNDLE
            else -> null
        }
        val id = o.optString("id")
        val name = o.optString("name")
        if (kind == null || !ID_RE.matches(id) || name.isBlank()) return null

        val headers = o.optJSONObject("headers")?.let { h ->
            buildMap {
                h.keys().asSequence().forEach { k ->
                    val v = h.opt(k)
                    if (v is String) put(k, v)
                }
            }
        }.orEmpty()
        val categories = o.optJSONObject("categories")?.let { c ->
            val map = LinkedHashMap<String, List<Int>>()
            c.keys().asSequence().forEach { key ->
                val list = mutableListOf<Int>()
                c.optJSONArray(key)?.let { arr ->
                    for (i in 0 until arr.length()) list += arr.optInt(i)
                }
                map[key] = list
            }
            map
        }.orEmpty()
        val adDomains = o.optJSONObject("adBlock")?.optJSONArray("domains")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.opt(it) as? String }
        }.orEmpty()
        val adKeywords = o.optJSONObject("adBlock")?.optJSONArray("urlKeywords")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.opt(it) as? String }
        }.orEmpty()

        var baseUrl = ""
        var fallback = emptyList<String>()
        var m3uUrl: String? = null
        var m3uInline: String? = null

        when (kind) {
            SourceKind.MACCMS -> {
                baseUrl = o.optString("baseUrl")
                if (baseUrl.isBlank()) {
                    issue(issues, "E_SCHEMA", "$where.baseUrl", "maccms 必须提供 baseUrl")
                } else if (!isHttpUrl(baseUrl) || baseUrl.length > 512) {
                    issue(issues, "E_URL", "$where.baseUrl", "baseUrl 必须是 http(s) 且 ≤512")
                }
                fallback = o.optJSONArray("fallbackBaseUrls")?.let { arr ->
                    if (arr.length() > 3) issue(issues, "E_SCHEMA", "$where.fallbackBaseUrls", "最多 3 项")
                    (0 until arr.length()).mapNotNull { arr.opt(it) as? String }.also { list ->
                        list.forEachIndexed { i, u ->
                            if (!isHttpUrl(u) || u.length > 512) issue(issues, "E_URL", "$where.fallbackBaseUrls[$i]", "必须是 http(s)")
                        }
                    }
                }.orEmpty()
                val flavor = o.optString("flavor", "macjson")
                if (flavor !in setOf("macjson", "maccms")) issue(issues, "E_SCHEMA", "$where.flavor", "flavor 非法：$flavor")
                val mode = o.optString("apiPathMode", "query")
                if (mode !in setOf("query", "path")) issue(issues, "E_SCHEMA", "$where.apiPathMode", "apiPathMode 非法：$mode")
                if (o.has("pageSize")) {
                    val ps = o.optInt("pageSize")
                    if (ps !in 10..50) issue(issues, "E_SCHEMA", "$where.pageSize", "pageSize 必须在 10..50")
                }
                if (o.has("classTtlMin")) {
                    val ttl = o.optInt("classTtlMin")
                    if (ttl !in 30..10080) issue(issues, "E_SCHEMA", "$where.classTtlMin", "classTtlMin 必须在 30..10080")
                }
                val unknownCats = categories.keys.filterNot { it in CATEGORY_KEYS }
                if (unknownCats.isNotEmpty()) issue(issues, "E_FIELD", "$where.categories", "未知分类键：$unknownCats")
            }
            SourceKind.M3U -> {
                validateM3u(o, where, issues)
                m3uUrl = o.optString("url").takeIf { it.isNotBlank() }
                m3uInline = o.optString("inline").takeIf { it.isNotBlank() }
            }
            SourceKind.BUNDLE -> Unit
        }

        return SourceConfig(
            id = id,
            name = name,
            kind = kind,
            baseUrl = baseUrl,
            fallbackBaseUrls = fallback,
            priority = o.optInt("priority", 100),
            enabled = o.optBoolean("enabled", true),
            headers = headers,
            timeoutMs = o.optInt("timeoutMs", 6000),
            pageSize = o.optInt("pageSize", 20),
            categories = categories,
            adBlockDomains = adDomains,
            adBlockKeywords = adKeywords,
            skipIntroSeconds = o.optInt("skipIntroSeconds", 0),
            maxQuality = o.optString("maxQuality", "auto"),
            preferredCodec = o.optString("preferredCodec", "auto"),
            m3uUrl = m3uUrl,
            m3uInline = m3uInline,
        )
    }

    private fun validateM3u(o: JSONObject, where: String, issues: MutableList<SourceIssue>) {
        val hasUrl = o.has("url") && o.optString("url").isNotBlank()
        val hasInline = o.has("inline") && o.optString("inline").isNotBlank()
        if (hasUrl == hasInline) issue(issues, "E_FIELD", where, "url 与 inline 必须二选一")
        if (hasUrl) {
            val url = o.optString("url")
            if (!isHttpUrl(url) || url.length > 512) issue(issues, "E_URL", "$where.url", "url 必须是 http(s) 且 ≤512")
        }
        if (hasInline && o.optString("inline").length > 524_288) {
            issue(issues, "E_SCHEMA", "$where.inline", "inline 最大 524288 字符")
        }
        if (o.has("maxBytes")) {
            val maxBytes = o.optInt("maxBytes")
            if (maxBytes !in 65_536..8_388_608) issue(issues, "E_SCHEMA", "$where.maxBytes", "maxBytes 必须在 65536..8388608")
        }
        if (o.has("refreshIntervalMin")) {
            val v = o.optInt("refreshIntervalMin")
            if (v !in 0..10_080) issue(issues, "E_SCHEMA", "$where.refreshIntervalMin", "必须在 0..10080")
        }
        val filters = o.optJSONObject("filters")
        if (filters != null) {
            val unknown = filters.keys().asSequence().filterNot { it in M3U_FILTER_KEYS }.toList()
            if (unknown.isNotEmpty()) issue(issues, "E_SCHEMA", "$where.filters", "未知字段：$unknown")
            filters.optString("excludeNameRegex").takeIf { it.isNotBlank() }?.let { regex ->
                try {
                    Regex(regex)
                } catch (t: Throwable) {
                    issue(issues, "E_REGEX", "$where.filters.excludeNameRegex", "正则无效：${t.message}")
                }
            }
        }
        val dedupe = o.optJSONObject("dedupe")
        if (dedupe != null) {
            val unknown = dedupe.keys().asSequence().filterNot { it in M3U_DEDUPE_KEYS }.toList()
            if (unknown.isNotEmpty()) issue(issues, "E_SCHEMA", "$where.dedupe", "未知字段：$unknown")
        }
        validateChannels(o, where, issues)
    }

    private fun validateChannels(o: JSONObject, where: String, issues: MutableList<SourceIssue>) {
        if (!o.has("channels")) return
        val channels = o.optJSONArray("channels")
        if (channels == null) {
            issue(issues, "E_CHANNEL", "$where.channels", "channels 必须是数组")
            return
        }
        if (channels.length() > MAX_CHANNELS) issue(issues, "E_CHANNEL", "$where.channels", "最多 $MAX_CHANNELS 条")
        val seenNames = HashSet<String>()
        val seenTvg = HashSet<String>()
        for (i in 0 until channels.length()) {
            val ch = channels.optJSONObject(i)
            val cw = "$where.channels[$i]"
            if (ch == null) {
                issue(issues, "E_CHANNEL", cw, "频道必须是对象")
                continue
            }
            val unknown = ch.keys().asSequence().filterNot { it in CHANNEL_KEYS }.toList()
            if (unknown.isNotEmpty()) issue(issues, "E_CHANNEL", cw, "未知字段：$unknown")
            val name = ch.optString("name")
            if (name.isBlank()) issue(issues, "E_CHANNEL", cw, "name 不能为空")
            val group = ch.optString("group").ifBlank { "其他" }
            val key = group.lowercase() + "|" + name.replace(Regex("\\s+"), "").lowercase()
            if (!seenNames.add(key)) issue(issues, "E_CHANNEL", cw, "频道重复：$group/$name")
            val tvg = ch.optString("tvgId").takeIf { it.isNotBlank() }
            if (tvg != null && !seenTvg.add(tvg.lowercase())) issue(issues, "E_CHANNEL", cw, "tvgId 重复：$tvg")

            val hasUrl = ch.has("url")
            val hasUrls = ch.has("urls")
            if (hasUrl == hasUrls) issue(issues, "E_CHANNEL", cw, "url 与 urls 必须二选一")
            val urls = when {
                hasUrl -> listOf(ch.optString("url"))
                hasUrls -> ch.optJSONArray("urls")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
                else -> emptyList()
            }
            if (urls.isEmpty()) issue(issues, "E_CHANNEL", cw, "至少需要一个播放地址")
            if (urls.size > MAX_CHANNEL_URLS) issue(issues, "E_CHANNEL", cw, "urls 最多 $MAX_CHANNEL_URLS 条")
            urls.forEachIndexed { idx, u -> if (!isHttpUrl(u)) issue(issues, "E_CHANNEL", "$cw.urls[$idx]", "播放地址必须是 http(s)") }
            ch.optString("logo").takeIf { it.isNotBlank() }?.let { logo ->
                if (!isHttpUrl(logo)) issue(issues, "E_CHANNEL", "$cw.logo", "logo 必须是 http(s)")
            }
            val desc = ch.optString("desc")
            if (desc.isNotBlank()) {
                if (desc.length > MAX_DESC_LEN) issue(issues, "E_CHANNEL", cw, "desc 超过 $MAX_DESC_LEN 字")
                if (desc.contains('\r') || desc.contains('\u0000')) issue(issues, "E_CHANNEL", cw, "desc 含非法控制字符")
            }
        }
    }

    // ------------------------------------------------------------------
    // 语义
    // ------------------------------------------------------------------

    private fun semanticChecks(imported: List<ImportedSource>, issues: MutableList<SourceIssue>) {
        val ids = HashSet<String>()
        val fingerprints = HashSet<String>()
        for (item in imported) {
            val cfg = item.config
            if (!ids.add(cfg.id)) issue(issues, "E_ID", cfg.id, "源 id 重复：${cfg.id}")
            val endpoint = if (cfg.kind == SourceKind.MACCMS) cfg.baseUrl else cfg.m3uUrl
            val fp = canonicalFingerprint(cfg.kind, endpoint)
            if (fp != null && !fingerprints.add(fp)) issue(issues, "E_ID", cfg.id, "端点指纹重复：$fp")
        }
    }

    fun canonicalFingerprint(kind: SourceKind, endpoint: String?): String? {
        if (endpoint.isNullOrBlank()) return null
        return try {
            val uri = URI(endpoint)
            val scheme = uri.scheme?.lowercase() ?: return null
            var port = uri.port
            if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) port = -1
            val host = uri.host?.lowercase() ?: return null
            val netloc = if (port > 0) "$host:$port" else host
            val query = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.sorted().joinToString("&")
            val path = uri.rawPath ?: ""
            "${kind.name.lowercase()}|$scheme://$netloc$path" + if (query.isNotEmpty()) "?$query" else ""
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun issue(issues: MutableList<SourceIssue>, code: String, path: String, message: String) {
        issues += SourceIssue(code, path, message)
    }

    private fun checkType(
        o: JSONObject,
        key: String,
        type: Class<*>,
        issues: MutableList<SourceIssue>,
        where: String,
        extra: (Any) -> Unit = {},
    ) {
        val value = o.opt(key) ?: return
        if (value == JSONObject.NULL) return
        val ok = when (type) {
            Boolean::class.java -> value is Boolean
            Int::class.java -> value is Int || value is Long
            else -> type.isInstance(value)
        }
        if (!ok) {
            issue(issues, "E_SCHEMA", "$where.$key", "字段类型错误，期望 ${type.simpleName}")
        } else {
            extra(value)
        }
    }

    private fun isHttpUrl(url: String): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
        return try {
            URI(url).host != null
        } catch (t: Throwable) {
            false
        }
    }

    private fun hostOf(url: String): String = try {
        URI(url).host?.lowercase().orEmpty()
    } catch (t: Throwable) {
        ""
    }

    private fun domainMatches(host: String, rule: String): Boolean {
        val normalizedHost = host.lowercase().trimEnd('.')
        val normalizedRule = rule.lowercase().trimEnd('.')
        if (normalizedHost.isEmpty() || normalizedRule.isEmpty()) return false
        if (normalizedRule.startsWith("*.")) {
            val base = normalizedRule.substring(2)
            return normalizedHost == base || normalizedHost.endsWith(".$base")
        }
        return normalizedHost == normalizedRule || normalizedHost.endsWith(".$normalizedRule")
    }

    fun sourceToJson(config: SourceConfig): JSONObject? {
        // 仅用于测试/展示；实际持久化保留导入原文
        val json = JSONObject()
            .put("schema", "tvapp.source.v1.1")
            .put("kind", config.kind.name.lowercase())
            .put("id", config.id)
            .put("name", config.name)
            .put("enabled", config.enabled)
            .put("priority", config.priority)
        if (config.kind == SourceKind.MACCMS) json.put("baseUrl", config.baseUrl)
        if (config.m3uUrl != null) json.put("url", config.m3uUrl)
        return json
    }
}
