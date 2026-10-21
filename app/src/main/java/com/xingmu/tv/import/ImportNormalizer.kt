package com.xingmu.tv.import

import com.xingmu.tv.core.Result
import com.xingmu.tv.data.SourceKind
import com.xingmu.tv.source.HttpLayer
import com.xingmu.tv.source.ParseOptions
import com.xingmu.tv.source.SourceIssue
import com.xingmu.tv.source.SourceIssueCodes
import com.xingmu.tv.source.SourceWarning
import com.xingmu.tv.source.UrlRules
import org.json.JSONObject

/**
 * 把「用户实际粘贴/上传的东西」统一成**一份 tvapp.source.v1.1 JSON 文档**，再交给
 * [com.xingmu.tv.source.SourceSpecParser] 走 L1/L2。
 *
 * 支持四种输入（docs/SOURCE_IMPORT_SPEC.md §2.3）：
 * 1. 完整 JSON（单源或 bundle）；
 * 2. m3u 文本（生成 kind=m3u + inline 的单源文档）；
 * 3. 裸 URL（自动识别 MacCMS / m3u / bundle）；
 * 4. 纯文本 URL 列表（每行一个，生成 inline m3u）。
 */
object ImportNormalizer {

    data class Normalized(
        val json: String?,
        val issue: SourceIssue? = null,
        val warnings: List<SourceWarning> = emptyList(),
    )

    private const val DEFAULT_UA = "okhttp/4.12.0"

    /** 文本入口。 */
    suspend fun fromText(text: String, filename: String?): Normalized {
        val t = text.trim().removePrefix("\uFEFF")
        if (t.isEmpty()) {
            return Normalized(null, SourceIssue(SourceIssueCodes.JSON, "内容为空"))
        }
        if (t.startsWith("{")) return Normalized(t)
        if (t.startsWith("[")) {
            return Normalized(null, SourceIssue(SourceIssueCodes.SCHEMA, "顶层必须是对象（不支持 JSON 数组）"))
        }
        val lower = t.lowercase()
        if (lower.contains("#extm3u")) {
            return Normalized(m3uDoc(t, nameFromFile(filename) ?: "导入的直播源", filename ?: t))
        }
        val nonEmptyLines = t.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (nonEmptyLines.size == 1 && UrlRules.isHttpUrl(nonEmptyLines[0])) {
            return fromUrl(nonEmptyLines[0], ParseOptions(), filename)
        }
        if (nonEmptyLines.isNotEmpty() && nonEmptyLines.all { UrlRules.isHttpUrl(it) }) {
            val body = buildString {
                appendLine("#EXTM3U")
                nonEmptyLines.forEachIndexed { i, u -> appendLine("#EXTINF:-1,频道${i + 1}\n$u") }
            }
            return Normalized(m3uDoc(body, nameFromFile(filename) ?: "导入的直播源", filename ?: t))
        }
        return Normalized(
            null,
            SourceIssue(
                SourceIssueCodes.JSON,
                "无法识别内容：需要 JSON、m3u 播放列表或 http(s) 地址",
            ),
        )
    }

    /** 单 URL：自动识别 MacCMS / m3u / bundle（bundle 会下载内容再解析）。 */
    suspend fun fromUrl(url: String, opts: ParseOptions, filename: String? = null): Normalized {
        val clean = url.trim()
        val problem = UrlRules.checkUrl(clean, 2048, opts.allowLan)
        if (problem != null) return Normalized(null, SourceIssue(SourceIssueCodes.URL, problem))
        val host = UrlRules.hostOf(clean)
        val name = host.removePrefix("www.").ifEmpty { "导入的源" }
        val id = generatedId(host, clean)
        val warnings = ArrayList<SourceWarning>()
        if (clean.startsWith("http://", true)) {
            warnings.add(SourceWarning(SourceIssueCodes.W_HTTP, "该地址使用明文 http，建议改用 https", id))
        }
        return when (UrlRules.guessKindFromUrl(clean)) {
            SourceKind.BUNDLE -> {
                val r = HttpLayer.getText(
                    clean,
                    emptyMap(),
                    timeoutMs = 6000,
                    maxBytes = opts.maxBytes,
                    userAgent = DEFAULT_UA,
                )
                when (r) {
                    is Result.Err -> Normalized(null, SourceIssue(SourceIssueCodes.URL, "下载订阅失败：${r.error.msg}"))
                    is Result.Ok -> {
                        val body = r.value
                        if (body.httpCode !in 200..299) {
                            Normalized(null, SourceIssue(SourceIssueCodes.URL, "下载订阅失败：HTTP ${body.httpCode}"))
                        } else if (!body.body.trim().startsWith("{")) {
                            Normalized(null, SourceIssue(SourceIssueCodes.JSON, "订阅内容不是 JSON"))
                        } else {
                            Normalized(body.body, warnings = warnings)
                        }
                    }
                }
            }
            SourceKind.M3U -> m3uSourceFromUrl(clean, name, id, warnings, opts)
            SourceKind.MACCMS -> maccmsOrM3u(clean, name, id, warnings, opts)
        }
    }

    private suspend fun m3uSourceFromUrl(
        url: String,
        name: String,
        id: String,
        warnings: MutableList<SourceWarning>,
        opts: ParseOptions,
    ): Normalized {
        val r = HttpLayer.getText(url, emptyMap(), timeoutMs = 6000, maxBytes = opts.maxBytes, userAgent = DEFAULT_UA)
        if (r is Result.Ok) {
            val body = r.value.body
            if (body.trim().startsWith("{")) {
                warnings.add(SourceWarning(SourceIssueCodes.W_UNVERIFIED, "地址后缀是 m3u，实际返回 JSON，已按 JSON 导入"))
                return Normalized(body, warnings = warnings)
            }
            if (!body.contains("#EXTM3U", ignoreCase = true)) {
                return Normalized(null, SourceIssue(SourceIssueCodes.URL, "该地址不是 m3u 播放列表（缺少 #EXTM3U）"))
            }
        } else {
            // 网络失败不阻断导入（离线可用），仅标记未验证
            warnings.add(SourceWarning(SourceIssueCodes.W_UNVERIFIED, "m3u 订阅暂时无法访问，已按未验证源保存"))
        }
        val doc = JSONObject()
        doc.put("schema", "tvapp.source.v1.1")
        doc.put("kind", "m3u")
        doc.put("id", id)
        doc.put("name", UrlRules.sanitizeName(name))
        doc.put("url", url)
        doc.put("refreshIntervalMin", 360)
        return Normalized(doc.toString(), warnings = warnings)
    }

    /**
     * 未写死类型（或 MacCMS）的地址：用 `ac=list&t=0` 探测自动补全。
     * - 探测拿到 code=1 → MacCMS；
     * - 响应像 m3u → m3u；
     * - 网络失败 → 仍然按 MacCMS 保存并标 W_UNVERIFIED（导入永不因网络失败而失败）；
     * - HTTP 200 但既不是 MacCMS 也不是 m3u → E_URL。
     */
    private suspend fun maccmsOrM3u(
        url: String,
        name: String,
        id: String,
        warnings: MutableList<SourceWarning>,
        opts: ParseOptions,
    ): Normalized {
        val (base, changed) = UrlRules.normalizeMaccmsBaseUrl(url)
        if (changed) warnings.add(SourceWarning(SourceIssueCodes.W_URL_NORMALIZED, "地址已规范为 $base", id))
        val probeUrl = if (base.contains("?")) "$base&ac=list&t=0" else "${base}ac=list&t=0"
        val r = HttpLayer.getText(probeUrl, emptyMap(), timeoutMs = 4000, maxBytes = 256 * 1024, userAgent = DEFAULT_UA)
        if (r is Result.Ok && r.value.httpCode in 200..299) {
            val body = r.value.body
            if (body.contains("#EXTM3U", ignoreCase = true)) {
                return m3uSourceFromUrl(url, name, id, warnings, opts)
            }
            val obj = try {
                JSONObject(body.trim().substring(body.indexOf('{').coerceAtLeast(0)))
            } catch (e: Exception) {
                null
            }
            if (obj == null) {
                return Normalized(
                    null,
                    SourceIssue(SourceIssueCodes.URL, "该地址既不是 MacCMS 接口，也不是 m3u（返回内容无法解析）"),
                )
            }
            if (obj.opt("code") == null) {
                return Normalized(null, SourceIssue(SourceIssueCodes.URL, "该地址不是 MacCMS 接口（响应缺少 code 字段）"))
            }
        } else if (r is Result.Ok) {
            return Normalized(null, SourceIssue(SourceIssueCodes.URL, "该地址返回 HTTP ${r.value.httpCode}"))
        } else {
            warnings.add(SourceWarning(SourceIssueCodes.W_UNVERIFIED, "地址暂时无法访问，已按未验证的 MacCMS 源保存"))
        }
        return Normalized(maccmsDoc(base, name, id), warnings = warnings)
    }

    fun maccmsDoc(baseUrl: String, name: String, id: String): String {
        val doc = JSONObject()
        doc.put("schema", "tvapp.source.v1.1")
        doc.put("kind", "maccms")
        doc.put("id", id)
        doc.put("name", UrlRules.sanitizeName(name))
        doc.put("baseUrl", baseUrl)
        doc.put("timeoutMs", 6000)
        return doc.toString()
    }

    fun m3uDoc(inline: String, name: String, seed: String): String {
        val doc = JSONObject()
        doc.put("schema", "tvapp.source.v1.1")
        doc.put("kind", "m3u")
        doc.put("id", generatedId(name, seed))
        doc.put("name", UrlRules.sanitizeName(name))
        doc.put("inline", inline)
        return doc.toString()
    }

    /** 稳定 id：主机名/文件名 + URL 哈希后 6 位（重复导入同一地址 = 同一 id，天然幂等）。 */
    fun generatedId(seedText: String, fullText: String): String {
        val base = UrlRules.sanitizeId(seedText, fallback = "imported")
        val hash = Integer.toHexString(fullText.hashCode())
        return UrlRules.sanitizeId("$base-${hash.takeLast(6)}")
    }

    fun nameFromFile(filename: String?): String? {
        if (filename.isNullOrBlank()) return null
        val base = filename.substringAfterLast('/').substringAfterLast('\\')
        val noExt = base.substringBeforeLast('.', base)
        return noExt.trim().take(40).ifEmpty { null }
    }
}
