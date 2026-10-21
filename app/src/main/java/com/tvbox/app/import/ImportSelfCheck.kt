package com.tvbox.app.import

import com.tvbox.app.source.AdBlockRules
import com.tvbox.app.source.M3uParser
import com.tvbox.app.source.MacCmsClient
import com.tvbox.app.source.ParseOptions
import com.tvbox.app.source.SourceIssueCodes
import com.tvbox.app.source.SourceSpecParser
import com.tvbox.app.source.UrlRules

/**
 * L0–L2 校验 + 纯函数的自检（无 Android Context、无网络）。
 *
 * 由于版本基线冻结、不允许改 build.gradle 增加 JUnit 依赖，这里用可直接在设备/盒子上运行的
 * 自检替代 JVM 单测：
 * ```
 * adb push app-debug.apk /data/local/tmp/tvbox-check.apk
 * adb shell CLASSPATH=/data/local/tmp/tvbox-check.apk app_process /system/bin \
 *     com.tvbox.app.import.ImportSelfCheck
 * ```
 * 退出码 0 = 全部通过；非 0 = 有失败（打印每条）。负例集合与 tools/validate_sources.py
 * 的 --selftest（旧 9 + 新 8）一一对应。
 */
object ImportSelfCheck {

    private val failures = ArrayList<String>()
    private var passed = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val fails = ArrayList<String>()
        val live = args.any { it == "live" || it == "--live" }
        val paths = args.filter { it != "live" && it != "--live" }
        if (live) {
            fails.addAll(liveCheck())
        }
        if (paths.isNotEmpty()) {
            // 可选：校验真实文件（设备上跑 `... ImportSelfCheck /data/local/tmp/sources.builtin.json`）
            for (path in paths) {
                val text = try {
                    java.io.File(path).readText(Charsets.UTF_8)
                } catch (t: Throwable) {
                    fails.add("读取 $path 失败：${t.message}")
                    continue
                }
                val r = SourceSpecParser.parseText(text, ParseOptions(maxBytes = 16 * 1024 * 1024))
                val summary = "文件 $path: accepted=${r.accepted.size} rejected=${r.rejected.size} " +
                    "warnings=${r.warnings.size} docError=${r.documentError?.code}"
                println(summary)
                if (r.documentError != null || r.accepted.isEmpty() || r.rejected.isNotEmpty()) {
                    fails.add(summary)
                }
            }
        }
        fails.addAll(run())
        println("==============================================")
        if (fails.isEmpty()) {
            println("ImportSelfCheck: 全部通过")
            System.exit(0)
        } else {
            println("ImportSelfCheck: ${fails.size} 项失败")
            fails.forEach { println("  [FAIL] $it") }
            System.exit(1)
        }
    }

    fun run(): List<String> {
        failures.clear()
        passed = 0
        positive()
        negative()
        helpers()
        println("----------------------------------------------")
        println("ImportSelfCheck: passed=$passed failed=${failures.size}")
        return failures.toList()
    }

    // ---------------------------------------------------------------- 联网自检（可选）

    /** 真机联网检查 bfzy1（MacCMS 主源）：分类树 → 列表 → 单条 detail。需设备可访问外网。 */
    private fun liveCheck(): List<String> {
        val out = ArrayList<String>()
        fun check(label: String, ok: Boolean, detail: String = "") {
            if (!ok) out.add("live $label：$detail")
            println("  [${if (ok) "ok" else "FAIL"}] live $label $detail")
        }
        val src = SourceSpecParser.parseText(LIVE_SRC).accepted.firstOrNull()
        if (src == null) {
            out.add("live 源解析失败")
            return out
        }
        kotlinx.coroutines.runBlocking {
            when (val r = com.tvbox.app.source.MacCmsClient.fetchClasses(src)) {
                is com.tvbox.app.core.Result.Err -> check("分类树", false, r.error.msg)
                is com.tvbox.app.core.Result.Ok -> check(
                    "分类树",
                    r.value.httpCode == 200 && r.value.code == 1 && r.value.categories.size > 5,
                    "http=${r.value.httpCode} code=${r.value.code} class=${r.value.categories.size} ${r.value.latencyMs}ms",
                )
            }
            when (val r = com.tvbox.app.source.MacCmsClient.fetchList(src, 20, 1, com.tvbox.app.source.SourceRepository.Sort.LATEST)) {
                is com.tvbox.app.core.Result.Err -> check("列表", false, r.error.msg)
                is com.tvbox.app.core.Result.Ok -> {
                    check("列表", r.value.items.isNotEmpty(), "items=${r.value.items.size} ${r.value.latencyMs}ms")
                    val first = r.value.items.firstOrNull()
                    if (first != null) {
                        when (val d = com.tvbox.app.source.MacCmsClient.fetchDetail(src, first.vodId)) {
                            is com.tvbox.app.core.Result.Err -> check("详情", false, d.error.msg)
                            is com.tvbox.app.core.Result.Ok -> check(
                                "详情",
                                d.value.episodesByFrom.values.any { it.isNotEmpty() },
                                "线路=${d.value.playFroms} 首集=${d.value.episodesByFrom.values.flatten().firstOrNull()?.title}",
                            )
                        }
                    }
                }
            }
            when (val r = com.tvbox.app.source.MacCmsClient.probeContent(src)) {
                is com.tvbox.app.core.Result.Err -> check("L4 内容探测", false, r.error.msg)
                is com.tvbox.app.core.Result.Ok -> check("L4 内容探测", true, "play_url 非空，${r.value.second}ms")
            }
        }
        return out
    }

    private val LIVE_SRC = """
        {"schema":"tvapp.source.v1.1","kind":"maccms","id":"bfzy1","name":"暴风资源(主)",
         "baseUrl":"https://macapi1.com/maccms/json/baofeng/","timeoutMs":8000,
         "headers":{"User-Agent":"okhttp/4.12.0","Referer":"https://bfzy1.tv/"},
         "categories":{"MOVIE":[20],"TV":[30],"ANIME":[39],"VARIETY":[45]}}
    """.trimIndent()

    // ---------------------------------------------------------------- 正例

    private fun positive() {
        expectAccept("v1 老配置", maccmsV1(), 1)
        expectAccept("v1.1 新字段", maccmsV11(), 1)
        expectAccept("v1.1 m3u 频道定义", m3uDoc(), 1)
        expectAccept("baseUrl 缺结尾斜杠（自动补全）", maccmsRaw("""{"schema":"tvapp.source.v1","kind":"maccms","id":"nodash","name":"x","baseUrl":"https://api.example.com/api.php/provide/vod"}"""), 1)
        expectWarn("baseUrl 自动补斜杠告警", maccmsRaw("""{"schema":"tvapp.source.v1","kind":"maccms","id":"nodash2","name":"x","baseUrl":"https://api.example.com/api.php/provide/vod"}"""), SourceIssueCodes.W_URL_NORMALIZED)
        expectWarn("http 明文告警", """{"schema":"tvapp.source.v1.1","kind":"bundle","id":"b-http","name":"h","sources":[{"schema":"tvapp.source.v1","kind":"maccms","id":"plain","name":"p","baseUrl":"http://api.example.com/"}]}""", SourceIssueCodes.W_HTTP)
        expectAcceptWith("允许内网源（allowLan=true）", """{"schema":"tvapp.source.v1","kind":"maccms","id":"lan-src","name":"x","baseUrl":"http://192.168.1.5/api.php/provide/vod/"}""", allowLan = true, expected = 1)
        expectReject("内网源默认拒绝", """{"schema":"tvapp.source.v1","kind":"maccms","id":"lan-src","name":"x","baseUrl":"http://192.168.1.5/api.php/provide/vod/"}""", SourceIssueCodes.URL)
        expectReject("空内容", "   ", SourceIssueCodes.JSON)
        expectReject("非法 JSON", "{not json", SourceIssueCodes.JSON)
        val decoded = SourceSpecParser.decodeText(ByteArray(64) { 'A'.code.toByte() }, maxBytes = 8)
        expectTrue("L0 超限 E_SIZE", decoded.issue?.code == SourceIssueCodes.SIZE)
        val bad = SourceSpecParser.decodeText(byteArrayOf(0x80.toByte()), maxBytes = 1024)
        expectTrue("L0 编码 E_ENCODING", bad.issue?.code == SourceIssueCodes.ENCODING)
    }

    private fun negative() {
        expectReject("缺 schema", """{"kind":"maccms","id":"a1","name":"a","baseUrl":"https://a.example.com/"}""", SourceIssueCodes.SCHEMA)
        expectReject("未知 kind", """{"schema":"tvapp.source.v1","kind":"tvbox","id":"a1","name":"a","baseUrl":"https://a.example.com/"}""", SourceIssueCodes.KIND)
        expectReject("非法 id", """{"schema":"tvapp.source.v1","kind":"maccms","id":"Bad ID!","name":"a","baseUrl":"https://a.example.com/"}""", SourceIssueCodes.ID)
        expectReject("file 协议", """{"schema":"tvapp.source.v1","kind":"maccms","id":"a1","name":"a","baseUrl":"file:///etc/passwd"}""", SourceIssueCodes.URL)
        expectReject("缺 baseUrl", """{"schema":"tvapp.source.v1","kind":"maccms","id":"a1","name":"a"}""", SourceIssueCodes.URL)
        expectReject("timeoutMs 越界", """{"schema":"tvapp.source.v1","kind":"maccms","id":"a1","name":"a","baseUrl":"https://a.example.com/","timeoutMs":999}""", SourceIssueCodes.FIELD)
        expectReject("未知字段", """{"schema":"tvapp.source.v1","kind":"maccms","id":"a1","name":"a","baseUrl":"https://a.example.com/","evil":1}""", SourceIssueCodes.FIELD)
        expectReject("m3u 双端点", """{"schema":"tvapp.source.v1","kind":"m3u","id":"m1","name":"m","url":"https://e.example.com/a.m3u","inline":"#EXTM3U"}""", SourceIssueCodes.FIELD)
        expectReject("内嵌 bundle", """{"schema":"tvapp.source.v1","kind":"bundle","id":"b1","name":"b","sources":[{"schema":"tvapp.source.v1","kind":"bundle","id":"b2","name":"b2","sources":[{"schema":"tvapp.source.v1","kind":"maccms","id":"c1","name":"c","baseUrl":"https://a.example.com/"}]}]}""", SourceIssueCodes.NESTED)
        expectReject("skipIntroSeconds=601", maccmsV11(skip = 601), SourceIssueCodes.FIELD)
        expectReject("adBlock.domains 非字符串", maccmsV11(adDomains = "[123]"), SourceIssueCodes.ADBLOCK)
        expectReject("maxQuality 非法", maccmsV11(maxQuality = "4k"), SourceIssueCodes.FIELD)
        expectReject("preferredCodec 非法", maccmsV11(codec = "av1"), SourceIssueCodes.FIELD)
        expectReject("adBlock 非对象", maccmsV11(rawAdBlock = "\"ads\""), SourceIssueCodes.ADBLOCK)
        expectReject("频道缺 URL", """{"schema":"tvapp.source.v1.1","kind":"m3u","id":"m2","name":"m","inline":"#EXTM3U","channels":[{"name":"CCTV-1"}]}""", SourceIssueCodes.CHANNEL)
        expectReject("频道重复", """{"schema":"tvapp.source.v1.1","kind":"m3u","id":"m2","name":"m","inline":"#EXTM3U","channels":[{"name":"CCTV-1","url":"https://e.example.com/a.m3u8"},{"name":"cctv-1 ","url":"https://e.example.com/b.m3u8"}]}""", SourceIssueCodes.CHANNEL)
        expectReject("EPG 自伤", m3uSelfHarm(), SourceIssueCodes.ADBLOCK)
        expectReject("bundle 内 id 重复", """{"schema":"tvapp.source.v1","kind":"bundle","id":"b3","name":"b","sources":[{"schema":"tvapp.source.v1","kind":"maccms","id":"d1","name":"d","baseUrl":"https://a.example.com/"},{"schema":"tvapp.source.v1","kind":"maccms","id":"d1","name":"d2","baseUrl":"https://b.example.com/"}]}""", SourceIssueCodes.ID)
        expectReject("bundle 端点指纹重复", """{"schema":"tvapp.source.v1","kind":"bundle","id":"b4","name":"b","sources":[{"schema":"tvapp.source.v1","kind":"maccms","id":"e1","name":"e","baseUrl":"https://same.example.com/api/"},{"schema":"tvapp.source.v1","kind":"maccms","id":"e2","name":"e2","baseUrl":"https://same.example.com/api/"}]}""", SourceIssueCodes.ID)
        // 内部字段 bundleId：用户导入必须严格拒绝；回读 DB 时允许（防止老版本 config_json 回读失败）
        val withBundleId = """{"schema":"tvapp.source.v1","kind":"maccms","id":"internal-1","name":"x","baseUrl":"https://a.example.com/","bundleId":"builtin-v1"}"""
        expectReject("用户导入带内部字段 bundleId", withBundleId, SourceIssueCodes.FIELD)
        val internalOk = SourceSpecParser.parseText(
            withBundleId,
            ParseOptions(allowInternalFields = true),
        )
        expectTrue("回读允许 bundleId", internalOk.accepted.size == 1)
    }

    // ---------------------------------------------------------------- 纯函数

    private fun helpers() {
        val cases = listOf(
            Triple("ads.doubleclick.net", "doubleclick.net", true),
            Triple("doubleclick.net", "doubleclick.net", true),
            Triple("x.admaster.com.cn", "*.admaster.com.cn", true),
            Triple("notadmaster.com.cn", "*.admaster.com.cn", false),
            Triple("doubleclick.net.evil.com", "doubleclick.net", false),
        )
        for ((host, rule, want) in cases) {
            expectTrue("域名规则 $host vs $rule", AdBlockRules.domainRuleMatches(host, rule) == want)
        }
        expectTrue(
            "端点指纹",
            UrlRules.fingerprint("maccms", "https://macapi1.com/maccms/json/baofeng/") ==
                "maccms|https://macapi1.com/maccms/json/baofeng/",
        )
        expectTrue(
            "端点指纹去默认端口",
            UrlRules.fingerprint("maccms", "https://a.example.com:443/x/") == "maccms|https://a.example.com/x/",
        )
        expectTrue("id 合法", UrlRules.ID_REGEX.matches(UrlRules.sanitizeId("macapi1.com")))
        expectTrue("id 非法输入被净化", UrlRules.ID_REGEX.matches(UrlRules.sanitizeId("Mac API!站点")))

        val m3u = "#EXTM3U\n#EXTINF:-1 tvg-id=\"cctv1\" group-title=\"央视\",CCTV-1\nhttps://e.example.com/c1.m3u8\n"
        val parsed = M3uParser.parse(m3u, "m-test")
        expectTrue("m3u 解析 1 个频道", parsed.channels.size == 1 && parsed.channels[0].tvgId == "cctv1")

        val eps = MacCmsClient.parseEpisodes("第01集\$https://e.example.com/1.m3u8#第02集\$https://e.example.com/2.m3u8")
        expectTrue("剧集解析 2 集", eps.size == 2 && eps[1].index == 2)

        val completions = ImportInputSpec.completions("macapi1", emptyList())
        expectTrue("域名补全 macapi1", completions.contains("https://macapi1.com/maccms/json/baofeng/"))
        expectTrue("域名补全 macapi 前缀", ImportInputSpec.completions("bfzy", emptyList()).any { it.contains("bfzyapi.com") })
        expectTrue("非法字符被过滤", ImportInputSpec.sanitize("a b\u0000c").isEmpty().not())

        val gen1 = ImportNormalizer.generatedId("macapi1.com", "https://macapi1.com/maccms/json/baofeng/")
        val gen2 = ImportNormalizer.generatedId("macapi1.com", "https://macapi1.com/maccms/json/baofeng/")
        expectTrue("生成 id 稳定且合法", gen1 == gen2 && UrlRules.ID_REGEX.matches(gen1))

        normalizerAndMultipart()
    }

    /** 输入识别 + multipart + 报告序列化（纯函数，设备上可直接跑）。 */
    private fun normalizerAndMultipart() {
        // 1) m3u 文本 → inline 源 → 校验通过
        val m3uText = "#EXTM3U\n#EXTINF:-1 tvg-id=\"cctv1\" group-title=\"央视\",CCTV-1\nhttps://e.example.com/c1.m3u8\n"
        val n1 = kotlinx.coroutines.runBlocking { ImportNormalizer.fromText(m3uText, "live.m3u") }
        val r1 = n1.json?.let { SourceSpecParser.parseText(it) }
        expectTrue("m3u 文本识别为 m3u 源", r1 != null && r1.accepted.size == 1 && r1.accepted[0].kind == com.tvbox.app.data.SourceKind.M3U)
        expectTrue("m3u 文本 inline 保留内容", r1?.accepted?.firstOrNull()?.inline?.contains("#EXTM3U") == true)

        // 2) 纯 URL 列表 → m3u inline
        val n2 = kotlinx.coroutines.runBlocking {
            ImportNormalizer.fromText("https://e.example.com/a.m3u8\nhttps://e.example.com/b.m3u8\n", "list.txt")
        }
        val r2 = n2.json?.let { SourceSpecParser.parseText(it) }
        expectTrue("URL 列表识别", r2 != null && r2.accepted.size == 1)

        // 3) 非法内容
        val n3 = kotlinx.coroutines.runBlocking { ImportNormalizer.fromText("hello world", null) }
        expectTrue("无法识别内容", n3.json == null && n3.issue?.code == SourceIssueCodes.JSON)

        // 4) multipart 解析
        val boundary = "----tvboxTest"
        val body = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"token\"\r\n\r\n" +
                "abc123\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"sources.json\"\r\n" +
                "Content-Type: application/json\r\n\r\n" +
                "{\"schema\":\"tvapp.source.v1\"}\r\n" +
                "--$boundary--\r\n"
            ).toByteArray(Charsets.UTF_8)
        val parts = MultipartForm.parse(body, boundary)
        expectTrue("multipart token", MultipartForm.field(parts, "token") == "abc123")
        expectTrue("multipart file", MultipartForm.file(parts)?.filename == "sources.json")

        // 5) 报告 JSON（H5 返回值）可序列化
        val report = ImportReport(
            origin = ImportOrigin.LAN,
            accepted = r1?.accepted ?: emptyList(),
            warnings = listOf(com.tvbox.app.source.SourceWarning(SourceIssueCodes.W_HTTP, "http")),
        )
        val wire = try {
            org.json.JSONObject(report.toWire()).toString()
        } catch (t: Throwable) {
            ""
        }
        expectTrue("报告 toWire 可序列化", wire.contains("\"ok\":true") && wire.contains("\"summary\""))
    }

    // ---------------------------------------------------------------- 断言

    private fun expectAccept(label: String, json: String, expected: Int) =
        expectAcceptWith(label, json, allowLan = false, expected = expected)

    private fun expectAcceptWith(label: String, json: String, allowLan: Boolean, expected: Int) {
        val r = SourceSpecParser.parseText(json, ParseOptions(allowLan = allowLan))
        if (r.documentError != null || r.accepted.size != expected) {
            failures.add("$label: 期望接受 $expected，实际 documentError=${r.documentError?.code} accepted=${r.accepted.size} rejected=${r.rejected.firstOrNull()?.code}")
        } else {
            passed++
        }
    }

    private fun expectReject(label: String, json: String, code: String) {
        val r = SourceSpecParser.parseText(json)
        val got = r.documentError?.code ?: r.rejected.firstOrNull()?.code
        if (got != code) {
            failures.add("$label: 期望 $code，实际 ${got ?: "通过（未拦截）"}")
        } else {
            passed++
        }
    }

    private fun expectWarn(label: String, json: String, code: String) {
        val r = SourceSpecParser.parseText(json)
        if (r.warnings.none { it.code == code }) {
            failures.add("$label: 期望警告 $code，实际 ${r.warnings.map { it.code }}")
        } else {
            passed++
        }
    }

    private fun expectTrue(label: String, condition: Boolean) {
        if (condition) passed++ else failures.add("$label: 断言失败")
    }

    // ---------------------------------------------------------------- 样例

    private fun maccmsRaw(json: String): String = json

    private fun maccmsV1(): String = """
        {"schema":"tvapp.source.v1","kind":"maccms","id":"ok1","name":"ok","baseUrl":"https://macapi1.com/maccms/json/baofeng/"}
    """.trimIndent()

    private fun maccmsV11(
        skip: Int = 90,
        adDomains: String = "[\"doubleclick.net\"]",
        maxQuality: String = "1080p",
        codec: String = "auto",
        rawAdBlock: String? = null,
    ): String {
        val ad = rawAdBlock ?: """{"enabled":true,"domains":$adDomains,"urlKeywords":["/ads/"]}"""
        return """
            {"schema":"tvapp.source.v1.1","kind":"maccms","id":"ok2","name":"ok2",
             "baseUrl":"https://macapi1.com/maccms/json/baofeng/",
             "adBlock":$ad,"skipIntroSeconds":$skip,"skipIntroCue":true,
             "maxQuality":"$maxQuality","preferredCodec":"$codec"}
        """.trimIndent()
    }

    private fun m3uDoc(): String = """
        {"schema":"tvapp.source.v1.1","kind":"m3u","id":"m1","name":"m","url":"https://e.example.com/a.m3u",
         "epgUrls":["https://e.example.com/epg.xml.gz"],
         "channels":[{"name":"CCTV-1 综合","urls":["https://e.example.com/c1.m3u8"],"group":"央视频道",
                      "tvgId":"cctv1","logo":"https://e.example.com/c1.png","desc":"综合频道"}]}
    """.trimIndent()

    private fun m3uSelfHarm(): String = """
        {"schema":"tvapp.source.v1.1","kind":"m3u","id":"m9","name":"m","url":"https://e.example.com/a.m3u",
         "epgUrls":["https://ads.example.com/e.xml"],"adBlock":{"domains":["ads.example.com"]}}
    """.trimIndent()
}
