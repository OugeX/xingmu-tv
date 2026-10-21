package com.tvbox.app.source

import com.tvbox.app.data.SourceKind
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

/**
 * tvapp.source.v1 / v1.1 校验管线 L0（文件）→ L1（结构）→ L2（语义）。
 *
 * 用 org.json 手写等价校验，规则逐条对齐 assets/sources.schema.json 与
 * tools/validate_sources.py（错误码见 [SourceIssueCodes]）。
 * L3 网络探测 / L4 内容探测在 SourceProbe 中（异步，不阻断导入）。
 */
object SourceSpecParser {

    private val COMMON_KEYS = setOf(
        "schema", "kind", "id", "name", "enabled", "priority", "note", "headers",
        "timeoutMs", "maxConcurrent", "epgUrls", "adBlock", "skipIntroSeconds",
        "skipIntroCue", "maxQuality", "preferredCodec",
    )
    private val MACCMS_KEYS = setOf(
        "baseUrl", "flavor", "apiPathMode", "fallbackBaseUrls", "pageSize", "categories", "classTtlMin",
    )
    private val M3U_KEYS = setOf(
        "url", "inline", "refreshIntervalMin", "maxBytes", "filters", "dedupe", "channels",
    )
    private val BUNDLE_KEYS = setOf(
        "version", "updatedAt", "refreshIntervalHour", "signature", "merge", "sources",
    )

    private val QUALITY = setOf("auto", "1080p", "720p", "480p")
    private val CODEC = setOf("h264", "hevc", "auto")
    private val FLAVOR = setOf("macjson", "maccms")
    private val API_PATH_MODE = setOf("query", "path")
    private val MERGE = setOf("byId", "replace")
    private val SIGNATURE_RE = Regex("^sha256:[0-9a-f]{64}$")
    private val DATETIME_RE = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})$")

    // ---------------------------------------------------------------- L0 文件

    /** 字节 → 文本：BOM 处理、严格 UTF-8，失败回退 GB18030（国内源常见）。 */
    fun decodeText(bytes: ByteArray, maxBytes: Int = 8 * 1024 * 1024): DecodedText {
        if (bytes.isEmpty()) {
            return DecodedText(null, SourceIssue(SourceIssueCodes.JSON, "内容为空"))
        }
        if (bytes.size > maxBytes) {
            return DecodedText(
                null,
                SourceIssue(SourceIssueCodes.SIZE, "文件超过上限（${maxBytes / 1024 / 1024} MB，实际 ${bytes.size / 1024} KB）"),
            )
        }
        var offset = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            offset = 3
        }
        val body = if (offset == 0) bytes else bytes.copyOfRange(offset, bytes.size)
        val strict = Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val utf8 = try {
            strict.decode(ByteBuffer.wrap(body)).toString()
        } catch (e: Exception) {
            null
        }
        if (utf8 != null && !utf8.contains('\u0000')) {
            return DecodedText(utf8, null)
        }
        val gb = try {
            Charset.forName("GB18030").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (e: Exception) {
            null
        }
        if (gb == null || gb.contains('\u0000')) {
            return DecodedText(null, SourceIssue(SourceIssueCodes.ENCODING, "文件编码无法识别（仅支持 UTF-8 / GB18030）"))
        }
        return DecodedText(
            gb,
            null,
            listOf(SourceWarning(SourceIssueCodes.ENCODING, "文件不是 UTF-8，已按 GB18030 解码")),
        )
    }

    // ---------------------------------------------------------------- 入口

    /** 文本入口（L0 已由调用方/本类完成）。 */
    fun parseText(text: String, opts: ParseOptions = ParseOptions()): ValidationResult {
        val trimmed = text.removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) {
            return failDoc(SourceIssueCodes.JSON, "内容为空")
        }
        val root = try {
            JSONObject(trimmed)
        } catch (e: JSONException) {
            return failDoc(SourceIssueCodes.JSON, "JSON 解析失败：${jsonErrorText(e.message, trimmed)}")
        }
        return parseObject(root, opts)
    }

    /** 对象入口：自动分派单源 / bundle。 */
    fun parseObject(root: JSONObject, opts: ParseOptions = ParseOptions()): ValidationResult {
        val schemaErr = checkSchema(root)
        if (schemaErr != null) return failDoc(schemaErr.code, schemaErr.message, schemaErr.field)

        val kindStr = stringOf(root, "kind")
        if (!root.has("kind")) return failDoc(SourceIssueCodes.KIND, "缺少 kind（maccms / m3u / bundle）", "kind")
        if (kindStr == null) return failDoc(SourceIssueCodes.KIND, "kind 必须是字符串", "kind")
        if (kindStr !in setOf("maccms", "m3u", "bundle")) {
            return failDoc(SourceIssueCodes.KIND, "未知源类型 \"$kindStr\"（只支持 maccms / m3u / bundle）", "kind")
        }
        if (kindStr != "bundle") {
            val entity = parseSourceObject(root, 0, null, opts)
            return ValidationResult(
                accepted = listOfNotNull(entity.source),
                rejected = entity.issues.map { RejectedSource(0, it.code, it.message, it.field) },
                warnings = entity.warnings,
            )
        }
        return parseBundle(root, opts)
    }

    // ---------------------------------------------------------------- bundle

    private fun parseBundle(root: JSONObject, opts: ParseOptions): ValidationResult {
        val warnings = ArrayList<SourceWarning>()
        val id = stringOf(root, "id")
        if (id == null || !UrlRules.ID_REGEX.matches(id)) {
            return failDoc(SourceIssueCodes.ID, "合集 id 非法（小写字母/数字开头，2..64 位）", "id")
        }
        val name = stringOf(root, "name")
        if (name == null || name.trim().isEmpty() || name.length > 40) {
            return failDoc(SourceIssueCodes.NAME, "合集名称缺失或超过 40 字", "name")
        }
        for (k in root.keys()) {
            if (k !in COMMON_KEYS && k !in BUNDLE_KEYS) {
                return failDoc(SourceIssueCodes.FIELD, "未知字段：$k", k)
            }
        }
        checkCommonFields(root, warnings)
            .firstOrNull()
            ?.let { return failDoc(it.code, it.message, it.field) }

        if (root.has("version")) {
            val v = intOf(root, "version")
            if (v == null || v < 0) return failDoc(SourceIssueCodes.FIELD, "version 必须是非负整数", "version")
        }
        val updatedAt = stringOf(root, "updatedAt")
        if (root.has("updatedAt") && (updatedAt == null || !DATETIME_RE.matches(updatedAt))) {
            return failDoc(SourceIssueCodes.FIELD, "updatedAt 必须是 ISO-8601 时间（如 2026-10-21T11:07:11Z）", "updatedAt")
        }
        var refreshHour = 24
        if (root.has("refreshIntervalHour")) {
            val v = intOf(root, "refreshIntervalHour")
            if (v == null || v !in 0..168) {
                return failDoc(SourceIssueCodes.FIELD, "refreshIntervalHour 必须在 0..168", "refreshIntervalHour")
            }
            refreshHour = v
        }
        val signature = stringOf(root, "signature")
        if (root.has("signature") && (signature == null || !SIGNATURE_RE.matches(signature))) {
            return failDoc(SourceIssueCodes.SIGNATURE, "签名格式非法（应为 sha256:<64 位小写十六进制>）", "signature")
        }
        val merge = stringOf(root, "merge") ?: "byId"
        if (merge !in MERGE) return failDoc(SourceIssueCodes.FIELD, "merge 只能是 byId / replace", "merge")
        if (signature != null) {
            warnings.add(SourceWarning(SourceIssueCodes.W_SIGNATURE_UNVERIFIED, "合集带签名：v0.1 仅校验格式，未做内容哈希校验"))
        }

        val sourcesArr = root.opt("sources") as? JSONArray
            ?: return failDoc(SourceIssueCodes.FIELD, "合集缺少 sources 数组", "sources")
        if (sourcesArr.length() == 0) return failDoc(SourceIssueCodes.LIMIT, "合集内至少需要 1 个源", "sources")
        if (sourcesArr.length() > 50) {
            return failDoc(SourceIssueCodes.LIMIT, "合集内源超过 50 个（当前 ${sourcesArr.length()}）", "sources")
        }

        val accepted = ArrayList<ParsedSource>()
        val rejected = ArrayList<RejectedSource>()
        val seenIds = HashSet<String>()
        val seenFps = HashSet<String>()
        for (i in 0 until sourcesArr.length()) {
            val child = sourcesArr.optJSONObject(i)
            if (child == null) {
                rejected.add(RejectedSource(i, SourceIssueCodes.FIELD, "sources[$i] 必须是对象", "sources[$i]"))
                continue
            }
            val entity = parseSourceObject(child, i, id, opts)
            if (entity.source == null) {
                entity.issues.forEach { rejected.add(RejectedSource(i, it.code, it.message, it.field)) }
                continue
            }
            val src = entity.source
            if (!seenIds.add(src.id)) {
                rejected.add(RejectedSource(i, SourceIssueCodes.ID, "源 id 重复：${src.id}", "id"))
                continue
            }
            val fp = src.fingerprint()
            if (fp.isNotEmpty() && !seenFps.add(fp)) {
                rejected.add(RejectedSource(i, SourceIssueCodes.ID, "端点指纹重复：${src.id}（${src.endpoint}）", "baseUrl"))
                continue
            }
            accepted.add(src)
            warnings.addAll(entity.warnings)
            if (src.endpoint.startsWith("http://", true)) {
                warnings.add(SourceWarning(SourceIssueCodes.W_HTTP, "源 ${src.id} 使用明文 http，建议改用 https", src.id))
            }
        }
        val bundle = ParsedBundle(
            id = id,
            name = name.trim(),
            version = intOf(root, "version") ?: 1,
            updatedAt = updatedAt,
            refreshIntervalHour = refreshHour,
            merge = merge,
            signature = signature,
            sources = accepted,
        )
        return ValidationResult(
            accepted = accepted,
            rejected = rejected,
            warnings = dedupeWarnings(warnings),
            bundle = bundle,
        )
    }

    // ---------------------------------------------------------------- 单源

    private data class Entity(
        val source: ParsedSource?,
        val issues: List<SourceIssue>,
        val warnings: List<SourceWarning>,
    )

    private fun parseSourceObject(
        src: JSONObject,
        index: Int,
        bundleId: String?,
        opts: ParseOptions,
    ): Entity {
        val issues = ArrayList<SourceIssue>()
        val warnings = ArrayList<SourceWarning>()
        fun err(code: String, msg: String, field: String? = null) {
            issues.add(SourceIssue(code, msg, field))
        }

        val schema = checkSchema(src)
        if (schema != null) err(schema.code, schema.message, schema.field)
        val kindStr = stringOf(src, "kind")
        if (kindStr == null) {
            err(SourceIssueCodes.KIND, "缺少 kind（maccms / m3u / bundle）", "kind")
        } else if (kindStr == "bundle") {
            err(SourceIssueCodes.NESTED, "合集不能包含合集（sources[$index]）", "kind")
        } else if (kindStr !in setOf("maccms", "m3u")) {
            err(SourceIssueCodes.KIND, "未知源类型 \"$kindStr\"", "kind")
        }
        val kind = when (kindStr) {
            "maccms" -> SourceKind.MACCMS
            "m3u" -> SourceKind.M3U
            else -> null
        }

        val allowed = COMMON_KEYS + when (kind) {
            SourceKind.MACCMS -> MACCMS_KEYS
            SourceKind.M3U -> M3U_KEYS
            else -> emptySet()
        }
        for (k in src.keys()) {
            if (k !in allowed && !(opts.allowInternalFields && k == "bundleId")) {
                err(SourceIssueCodes.FIELD, "未知字段：$k（拼写错误或该字段不属于 $kindStr）", k)
            }
        }

        val id = stringOf(src, "id")
        if (id == null) {
            err(SourceIssueCodes.ID, "缺少 id", "id")
        } else if (!UrlRules.ID_REGEX.matches(id)) {
            err(SourceIssueCodes.ID, "id 必须匹配 ^[a-z0-9][a-z0-9._-]{1,63}$（当前：$id）", "id")
        }
        val name = stringOf(src, "name")
        if (name == null) {
            err(SourceIssueCodes.NAME, "缺少 name", "name")
        } else if (name.length !in 1..40) {
            err(SourceIssueCodes.NAME, "name 长度必须在 1..40（当前 ${name.length}）", "name")
        }

        checkCommonFields(src, warnings).forEach { err(it.code, it.message, it.field) }

        var baseUrl = ""
        var fallback = emptyList<String>()
        var pageSize = 20
        var categories: Map<String, List<Int>> = emptyMap()
        var flavor = "macjson"
        var apiPathMode = "query"
        var classTtlMin = 1440
        var m3uUrl: String? = null
        var inline: String? = null
        var refreshMin = 360
        var maxBytes = 2 * 1024 * 1024
        var filters = M3uFilters()
        var dedupe = M3uDedupe()
        var channelDefs = emptyList<ChannelDef>()

        if (kind == SourceKind.MACCMS) {
            val rawBase = stringOf(src, "baseUrl")
            if (rawBase == null) {
                err(SourceIssueCodes.URL, "缺少 baseUrl", "baseUrl")
            } else {
                val problem = UrlRules.checkUrl(rawBase, 512, opts.allowLan)
                if (problem != null) {
                    err(SourceIssueCodes.URL, problem, "baseUrl")
                } else {
                    val (norm, changed) = UrlRules.normalizeMaccmsBaseUrl(rawBase)
                    baseUrl = norm
                    if (changed) {
                        warnings.add(SourceWarning(SourceIssueCodes.W_URL_NORMALIZED, "baseUrl 已规范为 $norm", id))
                    }
                    if (UrlRules.hasCredentials(rawBase)) {
                        warnings.add(SourceWarning(SourceIssueCodes.W_CREDENTIALS, "地址包含账号密码，日志已脱敏", id))
                    }
                }
            }
            if (src.has("flavor")) {
                val v = stringOf(src, "flavor")
                if (v == null || v !in FLAVOR) err(SourceIssueCodes.FIELD, "flavor 只能是 macjson / maccms", "flavor")
                else flavor = v
            }
            if (src.has("apiPathMode")) {
                val v = stringOf(src, "apiPathMode")
                if (v == null || v !in API_PATH_MODE) err(SourceIssueCodes.FIELD, "apiPathMode 只能是 query / path", "apiPathMode")
                else apiPathMode = v
            }
            if (src.has("fallbackBaseUrls")) {
                val arr = src.opt("fallbackBaseUrls") as? JSONArray
                if (arr == null) {
                    err(SourceIssueCodes.FIELD, "fallbackBaseUrls 必须是数组", "fallbackBaseUrls")
                } else {
                    if (arr.length() > 3) {
                        err(SourceIssueCodes.LIMIT, "fallbackBaseUrls 最多 3 个（当前 ${arr.length()}）", "fallbackBaseUrls")
                    }
                    val list = ArrayList<String>()
                    for (j in 0 until arr.length()) {
                        val u = arr.opt(j) as? String
                        if (u == null) {
                            err(SourceIssueCodes.URL, "fallbackBaseUrls[$j] 必须是字符串", "fallbackBaseUrls[$j]")
                            continue
                        }
                        val problem = UrlRules.checkUrl(u, 512, opts.allowLan)
                        if (problem != null) {
                            err(SourceIssueCodes.URL, "fallback[$j] $problem", "fallbackBaseUrls[$j]")
                        } else {
                            list.add(UrlRules.normalizeMaccmsBaseUrl(u).first)
                        }
                    }
                    fallback = list
                }
            }
            if (src.has("pageSize")) {
                val v = intOf(src, "pageSize")
                if (v == null || v !in 10..50) err(SourceIssueCodes.FIELD, "pageSize 必须在 10..50", "pageSize") else pageSize = v
            }
            if (src.has("categories")) {
                val obj = src.opt("categories") as? JSONObject
                if (obj == null) {
                    err(SourceIssueCodes.FIELD, "categories 必须是对象", "categories")
                } else {
                    val map = LinkedHashMap<String, List<Int>>()
                    for (key in obj.keys()) {
                        if (key !in CategoryKeys.ALL) {
                            err(SourceIssueCodes.FIELD, "未知分类键：$key（可用 ${CategoryKeys.ALL.joinToString("/")}）", "categories.$key")
                            continue
                        }
                        val arr = obj.opt(key) as? JSONArray
                        if (arr == null) {
                            err(SourceIssueCodes.FIELD, "categories.$key 必须是数组", "categories.$key")
                            continue
                        }
                        if (arr.length() > 10) err(SourceIssueCodes.FIELD, "categories.$key 最多 10 项", "categories.$key")
                        val ids = ArrayList<Int>()
                        for (j in 0 until arr.length()) {
                            val v = arr.opt(j) as? Number
                            if (v == null || v.toInt() < 1) {
                                err(SourceIssueCodes.FIELD, "categories.$key[$j] 必须是 ≥1 的整数", "categories.$key")
                            } else {
                                ids.add(v.toInt())
                            }
                        }
                        map[key] = ids
                    }
                    categories = map
                }
            }
            if (src.has("classTtlMin")) {
                val v = intOf(src, "classTtlMin")
                if (v == null || v !in 30..10080) err(SourceIssueCodes.FIELD, "classTtlMin 必须在 30..10080", "classTtlMin") else classTtlMin = v
            }
        }

        if (kind == SourceKind.M3U) {
            val hasUrl = src.has("url")
            val hasInline = src.has("inline")
            if (hasUrl == hasInline) err(SourceIssueCodes.FIELD, "url 与 inline 必须二选一", "url")
            if (hasUrl) {
                val u = stringOf(src, "url")
                if (u == null) {
                    err(SourceIssueCodes.URL, "url 必须是字符串", "url")
                } else {
                    val problem = UrlRules.checkUrl(u, 512, opts.allowLan)
                    if (problem != null) err(SourceIssueCodes.URL, problem, "url") else m3uUrl = u
                }
            }
            if (hasInline) {
                val s = stringOf(src, "inline")
                if (s == null) {
                    err(SourceIssueCodes.FIELD, "inline 必须是字符串", "inline")
                } else if (s.length > 524288) {
                    err(SourceIssueCodes.LIMIT, "inline 超过 512KB 上限", "inline")
                } else {
                    inline = s
                }
            }
            if (src.has("refreshIntervalMin")) {
                val v = intOf(src, "refreshIntervalMin")
                if (v == null || v !in 0..10080) err(SourceIssueCodes.FIELD, "refreshIntervalMin 必须在 0..10080", "refreshIntervalMin") else refreshMin = v
            }
            if (src.has("maxBytes")) {
                val v = intOf(src, "maxBytes")
                if (v == null || v !in 65536..8388608) err(SourceIssueCodes.FIELD, "maxBytes 必须在 65536..8388608", "maxBytes") else maxBytes = v
            }
            if (src.has("filters")) {
                val obj = src.opt("filters") as? JSONObject
                if (obj == null) {
                    err(SourceIssueCodes.FIELD, "filters 必须是对象", "filters")
                } else {
                    var inc = emptyList<String>()
                    var exc = emptyList<String>()
                    var regex: String? = null
                    for (k in obj.keys()) {
                        if (k !in setOf("includeGroups", "excludeGroups", "excludeNameRegex")) {
                            err(SourceIssueCodes.FIELD, "filters 未知字段：$k", "filters.$k")
                        }
                    }
                    fun groupList(key: String): List<String> {
                        val arr = obj.opt(key) as? JSONArray ?: return emptyList()
                        if (arr.length() > 20) err(SourceIssueCodes.FIELD, "filters.$key 最多 20 项", "filters.$key")
                        val out = ArrayList<String>()
                        for (j in 0 until arr.length()) {
                            val s = arr.opt(j) as? String
                            if (s == null || s.length > 40) {
                                err(SourceIssueCodes.FIELD, "filters.$key[$j] 必须是 ≤40 字的字符串", "filters.$key")
                            } else {
                                out.add(s)
                            }
                        }
                        return out
                    }
                    if (obj.has("includeGroups")) inc = groupList("includeGroups")
                    if (obj.has("excludeGroups")) exc = groupList("excludeGroups")
                    if (obj.has("excludeNameRegex")) {
                        val r = stringOf(obj, "excludeNameRegex")
                        if (r == null || r.length > 128) {
                            err(SourceIssueCodes.REGEX, "excludeNameRegex 必须是 ≤128 字的字符串", "filters.excludeNameRegex")
                        } else {
                            try {
                                Regex(r)
                                regex = r
                            } catch (e: Exception) {
                                err(SourceIssueCodes.REGEX, "正则无效：${e.message}", "filters.excludeNameRegex")
                            }
                        }
                    }
                    filters = M3uFilters(inc, exc, regex)
                }
            }
            if (src.has("dedupe")) {
                val obj = src.opt("dedupe") as? JSONObject
                if (obj == null) {
                    err(SourceIssueCodes.FIELD, "dedupe 必须是对象", "dedupe")
                } else {
                    for (k in obj.keys()) {
                        if (k !in setOf("byTvgId", "byName", "mergeUrls")) {
                            err(SourceIssueCodes.FIELD, "dedupe 未知字段：$k", "dedupe.$k")
                        } else if (boolOf(obj, k) == null) {
                            err(SourceIssueCodes.FIELD, "dedupe.$k 必须是布尔值", "dedupe.$k")
                        }
                    }
                    dedupe = M3uDedupe(
                        byTvgId = boolOf(obj, "byTvgId") ?: true,
                        byName = boolOf(obj, "byName") ?: true,
                        mergeUrls = boolOf(obj, "mergeUrls") ?: true,
                    )
                }
            }
            if (src.has("channels")) {
                val arr = src.opt("channels") as? JSONArray
                if (arr == null) {
                    err(SourceIssueCodes.CHANNEL, "channels 必须是数组", "channels")
                } else if (arr.length() > 5000) {
                    err(SourceIssueCodes.LIMIT, "channels 最多 5000 条", "channels")
                } else {
                    channelDefs = parseChannelDefs(arr) { code, msg, field -> err(code, msg, field) }
                }
            }
        }

        // ---- v1.1 广告拦截（L2 自伤检查）
        val adBlock = parseAdBlock(src, { code, msg, field -> err(code, msg, field) }, warnings, id)
        if (kind == SourceKind.MACCMS && baseUrl.isNotEmpty()) {
            val protected = ArrayList<String>()
            protected.add(baseUrl)
            protected.addAll(fallback)
            protected.addAll(epgListOf(src))
            val harm = AdBlockRules.selfHarm(adBlock.domains, protected)
            if (harm != null) {
                err(
                    SourceIssueCodes.ADBLOCK,
                    "广告规则会拦截源自身接口：${harm.first} 命中 ${harm.second}",
                    "adBlock.domains",
                )
            }
        }
        if (kind == SourceKind.M3U && m3uUrl != null) {
            val protected = ArrayList<String>()
            protected.add(m3uUrl!!)
            protected.addAll(epgListOf(src))
            val harm = AdBlockRules.selfHarm(adBlock.domains, protected)
            if (harm != null) {
                err(
                    SourceIssueCodes.ADBLOCK,
                    "广告规则会拦截订阅/EPG 地址：${harm.first} 命中 ${harm.second}",
                    "adBlock.domains",
                )
            }
        }

        if (issues.isNotEmpty() || kind == null || id == null || name == null) {
            return Entity(null, issues, warnings)
        }

        val raw = JSONObject(src.toString())
        // 规范化写回（便于 config_json 迁移与幂等比较）
        raw.put("schema", schemaOrV11(src))
        raw.put("kind", kindStr)
        raw.put("id", id)
        raw.put("name", name)
        raw.put("enabled", boolOf(src, "enabled") ?: true)
        raw.put("priority", intOf(src, "priority") ?: 100)
        if (kind == SourceKind.MACCMS) {
            raw.put("baseUrl", baseUrl)
            if (fallback.isNotEmpty()) raw.put("fallbackBaseUrls", JSONArray(fallback))
            else raw.remove("fallbackBaseUrls")
        }
        // bundleId 只作为内存/DB 列信息，不写回 config_json（保持配置与协议文件同构）

        val entity = ParsedSource(
            schema = schemaOrV11(src),
            kind = kind,
            id = id,
            name = name.trim(),
            enabled = boolOf(src, "enabled") ?: true,
            priority = intOf(src, "priority") ?: 100,
            note = stringOf(src, "note"),
            headers = headersOf(src, ::err),
            timeoutMs = intOf(src, "timeoutMs") ?: 6000,
            maxConcurrent = intOf(src, "maxConcurrent") ?: 2,
            baseUrl = baseUrl,
            flavor = flavor,
            apiPathMode = apiPathMode,
            fallbackBaseUrls = fallback,
            pageSize = pageSize,
            categories = categories,
            classTtlMin = classTtlMin,
            m3uUrl = m3uUrl,
            inline = inline,
            refreshIntervalMin = refreshMin,
            maxBytes = maxBytes,
            filters = filters,
            dedupe = dedupe,
            channelDefs = channelDefs,
            epgUrls = epgListOf(src),
            adBlock = adBlock,
            skipIntroSeconds = intOf(src, "skipIntroSeconds") ?: 0,
            skipIntroCue = boolOf(src, "skipIntroCue") ?: false,
            maxQuality = stringOf(src, "maxQuality") ?: "auto",
            preferredCodec = stringOf(src, "preferredCodec") ?: "auto",
            raw = raw,
            bundleId = bundleId,
        )
        return Entity(entity, emptyList(), dedupeWarnings(warnings))
    }

    // ---------------------------------------------------------------- 公共字段

    private fun checkSchema(src: JSONObject): SourceIssue? {
        if (!src.has("schema")) {
            return SourceIssue(SourceIssueCodes.SCHEMA, "缺少 schema（需要 tvapp.source.v1 或 tvapp.source.v1.1）", "schema")
        }
        val v = stringOf(src, "schema")
            ?: return SourceIssue(SourceIssueCodes.SCHEMA, "schema 必须是字符串", "schema")
        if (v != "tvapp.source.v1" && v != "tvapp.source.v1.1") {
            return SourceIssue(SourceIssueCodes.SCHEMA, "不支持的协议版本：$v（需要 tvapp.source.v1 / v1.1）", "schema")
        }
        return null
    }

    private fun schemaOrV11(src: JSONObject): String = stringOf(src, "schema") ?: "tvapp.source.v1.1"

    /** 公共字段校验；返回 issue 列表（调用方决定是整体拒绝还是逐条）。 */
    private fun checkCommonFields(src: JSONObject, warnings: MutableList<SourceWarning>): List<SourceIssue> {
        val out = ArrayList<SourceIssue>()
        fun err(code: String, msg: String, field: String? = null) {
            out.add(SourceIssue(code, msg, field))
        }
        if (src.has("enabled") && boolOf(src, "enabled") == null) err(SourceIssueCodes.FIELD, "enabled 必须是布尔值", "enabled")
        if (src.has("priority")) {
            val v = intOf(src, "priority")
            if (v == null || v !in 0..1000) err(SourceIssueCodes.FIELD, "priority 必须在 0..1000", "priority")
        }
        if (src.has("note")) {
            val v = stringOf(src, "note")
            if (v == null || v.length > 200) err(SourceIssueCodes.FIELD, "note 必须是 ≤200 字的字符串", "note")
        }
        if (src.has("headers")) {
            val obj = src.opt("headers") as? JSONObject
            if (obj == null) {
                err(SourceIssueCodes.FIELD, "headers 必须是对象", "headers")
            } else {
                if (obj.length() > 8) err(SourceIssueCodes.FIELD, "headers 最多 8 项", "headers")
                for (k in obj.keys()) {
                    val v = stringOf(obj, k)
                    if (!UrlRules.isHeaderKey(k)) {
                        err(SourceIssueCodes.FIELD, "header 名不在白名单：$k", "headers.$k")
                    } else if (v == null) {
                        err(SourceIssueCodes.FIELD, "header $k 必须是字符串", "headers.$k")
                    } else if (v.length > 512) {
                        err(SourceIssueCodes.FIELD, "header $k 超过 512 字符", "headers.$k")
                    } else if (v.contains('\r') || v.contains('\n')) {
                        err(SourceIssueCodes.FIELD, "header $k 不能包含换行（防头注入）", "headers.$k")
                    }
                }
            }
        }
        if (src.has("timeoutMs")) {
            val v = intOf(src, "timeoutMs")
            if (v == null || v !in 1000..15000) err(SourceIssueCodes.FIELD, "timeoutMs 需在 1000..15000", "timeoutMs")
        }
        if (src.has("maxConcurrent")) {
            val v = intOf(src, "maxConcurrent")
            if (v == null || v !in 1..4) err(SourceIssueCodes.FIELD, "maxConcurrent 必须在 1..4", "maxConcurrent")
        }
        if (src.has("epgUrls")) {
            val arr = src.opt("epgUrls") as? JSONArray
            if (arr == null) {
                err(SourceIssueCodes.EPG, "epgUrls 必须是数组", "epgUrls")
            } else {
                if (arr.length() > 3) err(SourceIssueCodes.EPG, "epgUrls 最多 3 条", "epgUrls")
                val seen = HashSet<String>()
                for (j in 0 until arr.length()) {
                    val u = arr.opt(j) as? String
                    if (u == null) {
                        err(SourceIssueCodes.EPG, "EPG 地址必须是字符串", "epgUrls[$j]")
                    } else if (!seen.add(u)) {
                        err(SourceIssueCodes.EPG, "EPG 地址重复：$u", "epgUrls[$j]")
                    } else {
                        val problem = UrlRules.checkUrl(u, 512, true)
                        if (problem != null) err(SourceIssueCodes.EPG, "EPG 地址必须是 http(s)：$u", "epgUrls[$j]")
                    }
                }
            }
        }
        if (src.has("skipIntroSeconds")) {
            val v = intOf(src, "skipIntroSeconds")
            if (v == null || v !in 0..600) err(SourceIssueCodes.FIELD, "skipIntroSeconds 必须在 0..600", "skipIntroSeconds")
        }
        if (src.has("skipIntroCue") && boolOf(src, "skipIntroCue") == null) {
            err(SourceIssueCodes.FIELD, "skipIntroCue 必须是布尔值", "skipIntroCue")
        }
        if (src.has("maxQuality")) {
            val v = stringOf(src, "maxQuality")
            if (v == null || v !in QUALITY) err(SourceIssueCodes.FIELD, "maxQuality 非法：$v", "maxQuality")
        }
        if (src.has("preferredCodec")) {
            val v = stringOf(src, "preferredCodec")
            if (v == null || v !in CODEC) err(SourceIssueCodes.FIELD, "preferredCodec 非法：$v", "preferredCodec")
        }
        if (src.has("adBlock")) {
            val obj = src.opt("adBlock") as? JSONObject
            if (obj == null) err(SourceIssueCodes.ADBLOCK, "adBlock 必须是对象", "adBlock")
        }
        if (src.has("enabled")) {
            val v = boolOf(src, "enabled")
            if (v == false) warnings.add(SourceWarning("W_DISABLED", "该源导入后默认停用"))
        }
        return out
    }

    private fun parseAdBlock(
        src: JSONObject,
        err: (String, String, String?) -> Unit,
        warnings: MutableList<SourceWarning>,
        sourceId: String?,
    ): AdBlock {
        if (!src.has("adBlock")) return AdBlock()
        val obj = src.opt("adBlock") as? JSONObject
        if (obj == null) return AdBlock()
        for (k in obj.keys()) {
            if (k !in setOf("enabled", "domains", "urlKeywords")) {
                err(SourceIssueCodes.ADBLOCK, "adBlock 未知字段：$k", "adBlock.$k")
            }
        }
        val enabled = boolOf(obj, "enabled") ?: true
        if (obj.has("enabled") && boolOf(obj, "enabled") == null) {
            err(SourceIssueCodes.ADBLOCK, "adBlock.enabled 必须是布尔值", "adBlock.enabled")
        }
        val domains = ArrayList<String>()
        val keywords = ArrayList<String>()
        if (obj.has("domains")) {
            val arr = obj.opt("domains") as? JSONArray
            if (arr == null) {
                err(SourceIssueCodes.ADBLOCK, "adBlock.domains 必须是数组", "adBlock.domains")
            } else {
                if (arr.length() > 50) err(SourceIssueCodes.ADBLOCK, "adBlock.domains 最多 50 条", "adBlock.domains")
                for (j in 0 until arr.length()) {
                    val d = arr.opt(j) as? String
                    if (d == null) {
                        err(SourceIssueCodes.ADBLOCK, "域名必须是字符串", "adBlock.domains[$j]")
                        continue
                    }
                    if (d != d.lowercase()) {
                        err(SourceIssueCodes.ADBLOCK, "域名必须小写：$d", "adBlock.domains[$j]")
                    }
                    if (d.isBlank() || d.contains('/') || d.contains(':') || d.contains(' ')) {
                        err(SourceIssueCodes.ADBLOCK, "域名格式非法（不要带路径/端口/scheme）：$d", "adBlock.domains[$j]")
                    } else if (!UrlRules.isDomainRule(d)) {
                        err(SourceIssueCodes.ADBLOCK, "域名格式非法：$d", "adBlock.domains[$j]")
                    }
                    if (domains.contains(d)) {
                        err(SourceIssueCodes.ADBLOCK, "域名重复：$d", "adBlock.domains[$j]")
                    }
                    domains.add(d)
                }
            }
        }
        if (obj.has("urlKeywords")) {
            val arr = obj.opt("urlKeywords") as? JSONArray
            if (arr == null) {
                err(SourceIssueCodes.ADBLOCK, "adBlock.urlKeywords 必须是数组", "adBlock.urlKeywords")
            } else {
                if (arr.length() > 100) err(SourceIssueCodes.ADBLOCK, "adBlock.urlKeywords 最多 100 条", "adBlock.urlKeywords")
                for (j in 0 until arr.length()) {
                    val k = arr.opt(j) as? String
                    if (k == null) {
                        err(SourceIssueCodes.ADBLOCK, "urlKeywords 必须是字符串", "adBlock.urlKeywords[$j]")
                        continue
                    }
                    if (k.isBlank()) {
                        err(SourceIssueCodes.ADBLOCK, "urlKeywords 不能为空白", "adBlock.urlKeywords[$j]")
                    } else if (k.trim() == "*") {
                        err(SourceIssueCodes.ADBLOCK, "urlKeywords 不允许 '*'（会拦截全部请求）", "adBlock.urlKeywords[$j]")
                    } else if (k.length > 64) {
                        err(SourceIssueCodes.ADBLOCK, "urlKeywords 单条最多 64 字符", "adBlock.urlKeywords[$j]")
                    }
                    if (keywords.contains(k)) {
                        err(SourceIssueCodes.ADBLOCK, "urlKeywords 重复：$k", "adBlock.urlKeywords[$j]")
                    }
                    keywords.add(k)
                }
            }
        }
        if (enabled && domains.isEmpty() && keywords.isEmpty()) {
            warnings.add(SourceWarning("W_ADBLOCK_EMPTY", "adBlock 已启用但规则为空", sourceId))
        }
        return AdBlock(enabled, domains, keywords)
    }

    private fun parseChannelDefs(arr: JSONArray, err: (String, String, String?) -> Unit): List<ChannelDef> {
        val out = ArrayList<ChannelDef>()
        val seenKeys = HashSet<String>()
        val seenTvg = HashSet<String>()
        for (i in 0 until arr.length()) {
            val ch = arr.optJSONObject(i)
            if (ch == null) {
                err(SourceIssueCodes.CHANNEL, "channels[$i] 必须是对象", "channels[$i]")
                continue
            }
            val where = "channels[$i]"
            for (k in ch.keys()) {
                if (k !in setOf("name", "url", "urls", "group", "logo", "tvgId", "epgId", "desc")) {
                    err(SourceIssueCodes.FIELD, "$where 未知字段：$k", "$where.$k")
                }
            }
            val name = stringOf(ch, "name")
            if (name == null || name.trim().isEmpty() || name.length > 60) {
                err(SourceIssueCodes.CHANNEL, "$where name 不能为空且 ≤60 字", "$where.name")
            }
            val hasUrl = ch.has("url")
            val hasUrls = ch.has("urls")
            if (hasUrl == hasUrls) {
                if (!hasUrl) err(SourceIssueCodes.CHANNEL, "$where 至少需要一个播放地址", "$where.url")
                else err(SourceIssueCodes.CHANNEL, "$where url 与 urls 必须二选一", "$where.url")
            }
            val urls = ArrayList<String>()
            if (hasUrl) {
                val u = stringOf(ch, "url")
                val problem = UrlRules.checkUrl(u, 2048, true)
                if (problem != null) err(SourceIssueCodes.CHANNEL, "$where 播放地址：$problem", "$where.url") else urls.add(u!!)
            }
            if (hasUrls) {
                val ua = ch.opt("urls") as? JSONArray
                if (ua == null) {
                    err(SourceIssueCodes.CHANNEL, "$where urls 必须是数组", "$where.urls")
                } else {
                    if (ua.length() > 8) err(SourceIssueCodes.CHANNEL, "$where urls 最多 8 条", "$where.urls")
                    for (j in 0 until ua.length()) {
                        val u = ua.opt(j) as? String
                        if (u == null) {
                            err(SourceIssueCodes.CHANNEL, "$where urls[$j] 必须是字符串", "$where.urls")
                            continue
                        }
                        if (!urls.contains(u)) urls.add(u)
                    }
                }
            }
            if (urls.isEmpty()) {
                err(SourceIssueCodes.CHANNEL, "$where 播放地址为空", "$where.url")
            }
            val group = stringOf(ch, "group")
            if (group != null && (group.isBlank() || group.length > 20)) {
                err(SourceIssueCodes.CHANNEL, "$where group 为 1..20 字", "$where.group")
            }
            val logo = stringOf(ch, "logo")
            if (logo != null && UrlRules.checkUrl(logo, 512, true) != null) {
                err(SourceIssueCodes.CHANNEL, "$where logo 必须是 http(s)：$logo", "$where.logo")
            }
            val tvgId = stringOf(ch, "tvgId")
            if (tvgId != null) {
                if (tvgId.isBlank() || tvgId.length > 128) {
                    err(SourceIssueCodes.CHANNEL, "$where tvgId 为 1..128 字", "$where.tvgId")
                } else if (!seenTvg.add(tvgId.lowercase())) {
                    err(SourceIssueCodes.CHANNEL, "$where tvgId 重复：$tvgId", "$where.tvgId")
                }
            }
            val epgId = stringOf(ch, "epgId")
            if (epgId != null && (epgId.isBlank() || epgId.length > 128)) {
                err(SourceIssueCodes.CHANNEL, "$where epgId 为 1..128 字", "$where.epgId")
            }
            val desc = stringOf(ch, "desc")
            if (desc != null) {
                if (desc.length > 200) err(SourceIssueCodes.CHANNEL, "$where desc 超过 200 字", "$where.desc")
                else if (desc.contains('\r') || desc.contains('\u0000')) err(SourceIssueCodes.CHANNEL, "$where desc 含非法控制字符", "$where.desc")
            }
            val key = (group ?: "其他").lowercase() + "|" + (name ?: "").replace(Regex("\\s+"), "").lowercase()
            if (!seenKeys.add(key)) {
                err(SourceIssueCodes.CHANNEL, "$where 频道重复：${group ?: "其他"}/${name ?: "?"}", "$where.name")
            }
            if (name != null && urls.isNotEmpty()) {
                out.add(ChannelDef(name.trim(), urls, group, logo, tvgId, epgId, desc))
            }
        }
        return out
    }

    private fun headersOf(src: JSONObject, err: (String, String, String?) -> Unit): Map<String, String> {
        val obj = src.opt("headers") as? JSONObject ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (k in obj.keys()) {
            val v = stringOf(obj, k)
            if (v != null) map[k] = v
        }
        return map
    }

    private fun epgListOf(src: JSONObject): List<String> {
        val arr = src.opt("epgUrls") as? JSONArray ?: return emptyList()
        return arr.toStringList()
    }

    // ---------------------------------------------------------------- 工具

    private fun failDoc(code: String, message: String, field: String? = null): ValidationResult =
        ValidationResult(emptyList(), emptyList(), emptyList(), RejectedSource(0, code, message, field))

    private fun dedupeWarnings(list: List<SourceWarning>): List<SourceWarning> {
        val seen = HashSet<String>()
        return list.filter { seen.add(it.code + "|" + it.message) }
    }

    private fun jsonErrorText(message: String?, text: String): String {
        if (message == null) return "无法解析"
        val m = Regex("at character (\\d+)").find(message)
        if (m != null) {
            val idx = m.groupValues[1].toIntOrNull() ?: return message
            val safe = idx.coerceIn(0, text.length)
            val line = text.substring(0, safe).count { it == '\n' } + 1
            return "第 $line 行附近：$message"
        }
        return message
    }

    private fun stringOf(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.get(key) as? String
    }

    private fun intOf(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.get(key)
        if (v !is Number) return null
        val d = v.toDouble()
        if (d != Math.floor(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) return null
        return v.toInt()
    }

    private fun boolOf(o: JSONObject, key: String): Boolean? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.get(key) as? Boolean
    }
}
