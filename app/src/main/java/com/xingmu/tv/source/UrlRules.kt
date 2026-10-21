package com.xingmu.tv.source

import com.xingmu.tv.data.SourceKind

/**
 * URL / id / 指纹相关的纯函数（无 Android 依赖，可单测）。
 *
 * 规则来源：docs/PLAN_SOURCES.md §4.2/§5.1/§11 + assets/sources.schema.json。
 * 与 tools/validate_sources.py 的 fingerprint()/hostname_of() 保持等价。
 */
object UrlRules {

    val ID_REGEX: Regex = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")

    private val HEADER_KEY_REGEX: Regex =
        Regex("^(User-Agent|Referer|Origin|Cookie|Authorization|Accept|Accept-Language|X-[A-Za-z0-9-]+)$")

    private val DOMAIN_REGEX: Regex =
        Regex("^(\\*\\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

    /** schema 里 `^https?://`，这里用不区分大小写的前缀判断（大小写混写也接受，随后规范化）。 */
    fun isHttpUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.trim()
        return u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
    }

    fun schemeOf(url: String): String {
        val i = url.indexOf("://")
        return if (i <= 0) "" else url.substring(0, i).lowercase()
    }

    /** 取 host（小写、去 userinfo、去端口；IPv6 去方括号）。解析失败返回 ""。 */
    fun hostOf(url: String): String {
        if (!isHttpUrl(url)) return ""
        var rest = url.trim().substringAfter("://")
        rest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val at = rest.lastIndexOf('@')
        if (at >= 0) rest = rest.substring(at + 1)
        if (rest.startsWith("[")) {
            val end = rest.indexOf(']')
            return if (end > 0) rest.substring(1, end).lowercase() else ""
        }
        return rest.substringBefore(':').lowercase()
    }

    fun portOf(url: String): Int? {
        if (!isHttpUrl(url)) return null
        var rest = url.trim().substringAfter("://")
        rest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val at = rest.lastIndexOf('@')
        if (at >= 0) rest = rest.substring(at + 1)
        if (rest.startsWith("[")) {
            val end = rest.indexOf(']')
            if (end < 0) return null
            val tail = rest.substring(end + 1)
            return if (tail.startsWith(":")) tail.substring(1).toIntOrNull() else null
        }
        return if (rest.contains(':')) rest.substringAfter(':').toIntOrNull() else null
    }

    fun hasCredentials(url: String): Boolean {
        if (!isHttpUrl(url)) return false
        val authority = url.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        return authority.contains('@')
    }

    /** 内网/回环地址（SSRF 防护，docs/PLAN_SOURCES.md §11）。 */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".lan")) return true
        if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true
        val v4 = h.trim('[', ']')
        val parts = v4.split('.')
        if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }) {
            val a = parts[0].toIntOrNull() ?: return false
            val b = parts[1].toIntOrNull() ?: return false
            if (a == 0 || a == 10 || a == 127) return true
            if (a == 169 && b == 254) return true
            if (a == 172 && b in 16..31) return true
            if (a == 192 && b == 168) return true
            if (a == 100 && b in 64..127) return true
            return false
        }
        return false
    }

    /**
     * 导入时的 URL 校验（L2）。返回错误文案，null 表示通过。
     * http 明文允许（国内源现实），但由调用方补 W_HTTP 警告。
     */
    fun checkUrl(url: String?, maxLen: Int = 2048, allowLan: Boolean = false): String? {
        if (url.isNullOrBlank()) return "地址不能为空"
        if (url.length > maxLen) return "地址超过 $maxLen 字符"
        if (!isHttpUrl(url)) return "只支持 http(s) 地址（当前：${schemeOf(url).ifEmpty { "空" }}）"
        val host = hostOf(url)
        if (host.isEmpty()) return "地址缺少主机名"
        if (host.contains(' ') || url.any { it == '\r' || it == '\n' || it == '\u0000' }) return "地址含非法字符"
        if (!allowLan && isPrivateHost(host)) return "不允许内网/回环地址（$host）"
        return null
    }

    fun isHeaderKey(key: String): Boolean = HEADER_KEY_REGEX.matches(key)

    fun isDomainRule(rule: String): Boolean = DOMAIN_REGEX.matches(rule)

    /** 端点指纹：kind|scheme://host[:port]path[?a=1&b=2]（与 validate_sources.py 等价）。 */
    fun fingerprint(kind: String, endpoint: String): String {
        if (endpoint.isBlank()) return ""
        val scheme = schemeOf(endpoint).ifEmpty { "http" }
        val host = hostOf(endpoint)
        var port = portOf(endpoint)
        if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) port = null
        val afterAuthority = endpoint.trim()
            .substringAfter("://")
            .substringAfter('/', "")
        val path = if (afterAuthority.contains('?')) {
            "/" + afterAuthority.substringBefore('?').substringBefore('#')
        } else {
            "/" + afterAuthority.substringBefore('#')
        }
        val rawQuery = if (endpoint.contains('?')) endpoint.substringAfter('?').substringBefore('#') else ""
        val query = rawQuery.split('&').filter { it.isNotBlank() }.sorted().joinToString("&")
        val netloc = if (port == null) host else "$host:$port"
        val base = "$kind|${scheme.lowercase()}://$netloc$path"
        return if (query.isEmpty()) base else "$base?$query"
    }

    /**
     * maccms baseUrl 规范化：去空白、去 `?ac=...` 查询串、去 fragment、补结尾斜杠。
     * @return Pair(规范化地址, 是否改动过)
     */
    fun normalizeMaccmsBaseUrl(raw: String): Pair<String, Boolean> {
        var s = raw.trim()
        var changed = false
        val q = s.indexOf('?')
        if (q >= 0) {
            s = s.substring(0, q)
            changed = true
        }
        val h = s.indexOf('#')
        if (h >= 0) {
            s = s.substring(0, h)
            changed = true
        }
        if (s.isNotEmpty() && !s.endsWith("/")) {
            s += "/"
            changed = true
        }
        return s to changed
    }

    /** 由主机名/文件名生成合法 id（小写、只留 [a-z0-9._-]，2..64 位）。 */
    fun sanitizeId(raw: String, fallback: String = "imported"): String {
        var s = raw.trim().lowercase()
            .replace(Regex("^https?://"), "")
            .replace(Regex("[^a-z0-9._-]"), "-")
            .trim('-', '.', '_')
            .take(56)
        if (s.isEmpty() || !s[0].isLetterOrDigit()) s = fallback + s
        if (s.length < 2) s += "-s"
        if (!ID_REGEX.matches(s)) {
            s = s.replace(Regex("[^a-z0-9._-]"), "-").take(56)
            if (s.length < 2) s = "imported-01"
        }
        return s
    }

    fun sanitizeName(raw: String, fallback: String = "导入的源"): String {
        val s = raw.trim().replace(Regex("\\s+"), " ").take(40)
        return s.ifBlank { fallback }
    }

    /** 从 URL 猜源类型：bundle(.json) / m3u(.m3u/.m3u8) / maccms（含 api.php、provide/vod、maccms 路径）。 */
    fun guessKindFromUrl(url: String): SourceKind {
        val path = url.lowercase().substringBefore('?')
        if (path.endsWith(".json")) return SourceKind.BUNDLE
        if (path.endsWith(".m3u") || path.endsWith(".m3u8")) return SourceKind.M3U
        if (path.contains("api.php") || path.contains("provide/vod") || path.contains("maccms")) {
            return SourceKind.MACCMS
        }
        return SourceKind.MACCMS
    }

    /** 日志/导出脱敏：把 user:pass@ 换成 ***@。 */
    fun maskCredentials(url: String): String =
        url.replace(Regex("://[^/@\\s]+@"), "://***@")
}
