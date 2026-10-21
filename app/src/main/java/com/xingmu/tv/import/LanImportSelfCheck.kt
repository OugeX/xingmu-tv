package com.xingmu.tv.import

import com.xingmu.tv.source.ProbeOutcome
import com.xingmu.tv.source.ProbeState
import com.xingmu.tv.source.SourceSpecParser
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * 局域网导入服务的端到端自检（在设备上真开 ServerSocket + 真发 HTTP）。
 *
 * 用法（设备/盒子，不需要 App UI）：
 * ```
 * adb shell CLASSPATH=/data/local/tmp/tvbox-check.apk app_process /system/bin \
 *     com.xingmu.tv.import.LanImportSelfCheck 18088
 * ```
 * 覆盖：H5 页/token 页、错误 token 410、配对码 403/成功、multipart 文件导入、
 * JSON 粘贴导入、token 单次有效、成功后自动关闭、请求体 413、Content-Type 415。
 * 绑定 127.0.0.1，验证完立即关闭；不影响电视上正在运行的 App。
 */
object LanImportSelfCheck {

    private val failures = ArrayList<String>()
    private var passed = 0
    private var port = 18088
    private var useLan = false

    @JvmStatic
    fun main(args: Array<String>) {
        port = args.firstOrNull { it.toIntOrNull() != null }?.toInt() ?: 18088
        useLan = args.any { it == "lan" }
        println("== LanImportSelfCheck @${if (useLan) "LAN" else "127.0.0.1"}:$port ==")
        sessionOne()
        sessionTwo()
        println("----------------------------------------------")
        if (failures.isEmpty()) {
            println("LanImportSelfCheck: passed=$passed failed=0 全部通过")
            System.exit(0)
        } else {
            println("LanImportSelfCheck: passed=$passed failed=${failures.size}")
            failures.forEach { println("  [FAIL] $it") }
            System.exit(1)
        }
    }

    // ---------------------------------------------------------------- 会话 1：配对码 + multipart

    private fun sessionOne() {
        val received = ArrayList<ImportReport>()
        val server = newServer(stopAfterSuccess = false, sink = received)
        val session = server.start()
        if (session == null) {
            failures.add("服务启动失败（端口 $port）")
            return
        }
        try {
            val ping = http("GET", session.serviceUrl + "/api/ping")
            expect("ping 200", ping.status == 200 && ping.body.contains("\"ok\":true"))

            val qr = QrCodes.bitmap(session.importUrl, 240)
            expect("二维码生成 240px", qr != null && qr.width == 240 && qr.height == 240)

            val home = http("GET", session.serviceUrl + "/")
            expect("首页 200", home.status == 200)
            expect("首页是 H5", home.body.contains("导入播放源"))
            expect("首页不泄露配对码", !home.body.contains(session.pairingCode))

            val tokenPage = http("GET", session.importUrl)
            expect("token 页 200", tokenPage.status == 200)
            expect("token 页注入 token", tokenPage.body.contains(session.token))
            expect("token 页注入配对码", tokenPage.body.contains("__PAIRING__").not() && tokenPage.body.contains(session.pairingCode))

            val badToken = http("GET", session.serviceUrl + "/i/not-a-token")
            expect("错误 token 410", badToken.status == 410)

            val wrongPair = http(
                "POST",
                session.serviceUrl + "/api/pair",
                """{"code":"000000"}""".toByteArray(),
                "application/json",
            )
            expect("错误配对码 403", wrongPair.status == 403 && wrongPair.body.contains("E_PAIR"))

            val pair = http(
                "POST",
                session.serviceUrl + "/api/pair",
                """{"code":"${session.pairingCode}"}""".toByteArray(),
                "application/json",
            )
            expect("正确配对码返回 token", pair.status == 200 && pair.body.contains(session.token))

            val boundary = "----tvboxSelfCheck"
            val fileJson = """{"schema":"tvapp.source.v1","kind":"maccms","id":"self-test","name":"自检源","baseUrl":"https://macapi1.com/maccms/json/baofeng/"}"""
            val multipartBody = buildString {
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"token\"\r\n\r\n")
                append(session.token).append("\r\n")
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"sources.json\"\r\n")
                append("Content-Type: application/json\r\n\r\n")
                append(fileJson).append("\r\n")
                append("--$boundary--\r\n")
            }.toByteArray(Charsets.UTF_8)
            val imported = http(
                "POST",
                session.serviceUrl + "/api/import",
                multipartBody,
                "multipart/form-data; boundary=$boundary",
            )
            expect("multipart 导入 200", imported.status == 200)
            expect("multipart 导入成功文案", imported.body.contains("\"ok\":true") && imported.body.contains("已导入 1 个源"))
            // onReport 在响应 flush 之后回调，给 1s 轮询窗口避免测试竞态
            var waited = 0
            while (received.isEmpty() && waited < 1000) {
                Thread.sleep(50)
                waited += 50
            }
            expect("导入回调上报", received.size == 1)

            val reused = http(
                "POST",
                session.serviceUrl + "/api/import",
                """{"token":"${session.token}","payload":"{}"}""".toByteArray(),
                "application/json",
            )
            expect("token 单次有效（第二次 410）", reused.status == 410)

            val stillAlive = http("GET", session.serviceUrl + "/api/ping")
            expect("stopAfterSuccess=false 时仍存活", stillAlive.status == 200)
        } finally {
            server.close("自检结束")
        }
        expect("close 后连接被拒绝", runCatching { http("GET", session.serviceUrl + "/api/ping", timeoutMs = 1500) }.isFailure)
    }

    // ---------------------------------------------------------------- 会话 2：JSON 粘贴 + 自动关闭 + 限制

    private fun sessionTwo() {
        val server = newServer(stopAfterSuccess = true, sink = ArrayList())
        val session = server.start()
        if (session == null) {
            failures.add("第二次启动失败（端口 $port 未释放）")
            return
        }
        try {
            val oversize = rawRequest(
                session.ip,
                "POST /api/import HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: 3000000\r\n\r\n",
            )
            expect("超过 2MB 返回 413", oversize.contains("413"))

            val badType = http(
                "POST",
                session.serviceUrl + "/api/import",
                byteArrayOf(1, 2, 3),
                "image/png",
            )
            expect("Content-Type 白名单 415", badType.status == 415)

            val payload = """{"schema":"tvapp.source.v1","kind":"maccms","id":"self-test2","name":"自检源2","baseUrl":"https://macapi1.com/maccms/json/baofeng/"}"""
            val import = http(
                "POST",
                session.serviceUrl + "/api/import",
                """{"token":"${session.token}","payload":${quote(payload)}}""".toByteArray(),
                "application/json",
            )
            expect("JSON 粘贴导入 200", import.status == 200 && import.body.contains("\"ok\":true"))
            Thread.sleep(700)
            expect("成功后服务自动关闭", !server.isRunning)
        } finally {
            server.close("自检结束")
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun newServer(
        stopAfterSuccess: Boolean,
        sink: MutableList<ImportReport>,
    ): LanImportServer = LanImportServer(
        context = null,
        executor = { submission -> fakeReport(submission) },
        onReport = { report -> sink.add(report) },
        stopAfterSuccess = stopAfterSuccess,
        preferredPort = port,
        bindIpOverride = if (useLan) null else "127.0.0.1",
    )

    private fun fakeReport(submission: LanImportServer.Submission): ImportReport {
        val json = submission.text
            ?: submission.bytes?.toString(Charsets.UTF_8)
            ?: """{"schema":"tvapp.source.v1","kind":"maccms","id":"self-test","name":"自检源","baseUrl":"https://macapi1.com/maccms/json/baofeng/"}"""
        val accepted = SourceSpecParser.parseText(json.take(64 * 1024)).accepted
        val probes = accepted.associate { src ->
            src.id to ProbeOutcome(
                sourceId = src.id,
                state = ProbeState.OK,
                httpCode = 200,
                latencyMs = 12,
                classCount = 5,
                channelCount = 2,
                contentOk = true,
            )
        }
        return ImportReport(
            origin = ImportOrigin.LAN,
            accepted = accepted,
            probes = probes,
            durationMs = 3,
            persisted = accepted.size,
        )
    }

    private data class HttpResponse(val status: Int, val body: String)

    private fun http(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
        timeoutMs: Int = 6000,
    ): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = false
        if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
        if (body != null) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        return HttpResponse(status, text)
    }

    /** 原始 socket 请求（用于测 413 这类不读 body 的响应）。 */
    private fun rawRequest(host: String, request: String): String {
        Socket(host, port).use { socket ->
            socket.soTimeout = 4000
            val out: OutputStream = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val input = BufferedInputStream(socket.getInputStream())
            val buf = ByteArray(1024)
            val n = input.read(buf)
            return if (n <= 0) "" else String(buf, 0, n, Charsets.ISO_8859_1)
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun expect(label: String, condition: Boolean) {
        if (condition) {
            passed++
            println("  [ok]   $label")
        } else {
            failures.add(label)
            println("  [FAIL] $label")
        }
    }
}
