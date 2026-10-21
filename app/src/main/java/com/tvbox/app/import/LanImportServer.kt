package com.tvbox.app.import

import android.content.Context
import com.tvbox.app.core.TvLog
import com.tvbox.app.source.SourceIssueCodes
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网导入 HTTP 服务（ServerSocket 自实现，无第三方 server 依赖）。
 *
 * 生命周期（SOURCE_IMPORT_SPEC §2.5）：
 * - 只在导入页 `start()`..`stop()` 之间存活；`stop()` 关闭监听、线程池与定时器；
 * - 端口从 8088 起，被占用自动 +1（最多试 20 个）；
 * - **只绑定一个局域网 IPv4**（无 WiFi 时退化为回环，手机扫了也连不上，UI 需提示）；
 * - token 一次性：5 分钟过期、成功导入后立即失效，服务随即关闭（stopAfterSuccess）；
 * - 单请求体 ≤2MB、最多 60 次请求 / 5 次失败即关闭、同一时刻只处理一个导入。
 */
class LanImportServer(
    context: Context?,
    private val executor: (Submission) -> ImportReport,
    private val onReport: (ImportReport) -> Unit,
    private val stopAfterSuccess: Boolean = true,
    private val preferredPort: Int = DEFAULT_PORT,
    private val bindIpOverride: String? = null,
) {

    /** 一次提交（H5 的三种入口归一后交给 [executor]）。 */
    data class Submission(
        val text: String? = null,
        val url: String? = null,
        val bytes: ByteArray? = null,
        val filename: String? = null,
    )

    /** 生产用法：把 pipeline 适配成 executor（runBlocking 只在服务的 worker 线程发生）。 */
    constructor(
        context: Context,
        pipeline: ImportPipeline,
        onReport: (ImportReport) -> Unit,
        stopAfterSuccess: Boolean = true,
        preferredPort: Int = DEFAULT_PORT,
    ) : this(
        context = context,
        executor = { submission ->
            kotlinx.coroutines.runBlocking {
                val options = ImportOptions(
                    origin = ImportOrigin.LAN,
                    maxBytes = MAX_FILE_BYTES,
                    allowLan = false,
                    filename = submission.filename,
                    probe = true,
                )
                when {
                    submission.bytes != null -> pipeline.importBytes(submission.bytes, options)
                    submission.url != null -> pipeline.importUrl(submission.url, options.copy(origin = ImportOrigin.LAN))
                    submission.text != null -> pipeline.importText(submission.text, options)
                    else -> ImportReport.failure(ImportOrigin.LAN, "E_REQUEST", "没有可导入的内容")
                }
            }
        },
        onReport = onReport,
        stopAfterSuccess = stopAfterSuccess,
        preferredPort = preferredPort,
    )

    data class Session(
        val token: String,
        val pairingCode: String,
        val ip: String,
        val port: Int,
        /** http://ip:port */
        val serviceUrl: String,
        /** http://ip:port/i/<token>（二维码内容） */
        val importUrl: String,
        val expiresAt: Long,
    )

    private val appContext: Context? = context?.applicationContext
    private val pageTemplate: String by lazy { appContext?.let { ImportPage.load(it) } ?: ImportPage.fallbackHtml }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var stopped = false

    private val used = AtomicBoolean(false)
    private val importing = AtomicBoolean(false)
    private val requestCount = AtomicInteger(0)
    private val failedAttempts = AtomicInteger(0)
    private val workers: ThreadPoolExecutor = Executors.newFixedThreadPool(4) as ThreadPoolExecutor
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "lan-import-timer").apply { isDaemon = true }
    }

    @Volatile
    private var acceptThread: Thread? = null

    fun currentSession(): Session? = session

    val isRunning: Boolean get() = serverSocket?.isClosed == false && !stopped

    /** 启动服务；端口全被占用/无网卡时返回 null（UI 提示改用 U 盘或遥控器输入）。 */
    fun start(): Session? {
        if (isRunning) return session
        stopped = false
        val lan = LocalNetwork.lanIpv4()
        val ip = bindIpOverride ?: lan?.ip ?: "127.0.0.1"
        if (bindIpOverride == null && lan == null) {
            TvLog.w(TAG, "未找到局域网 IPv4，服务仅绑定回环（手机无法访问）")
        }
        var port = preferredPort
        var lastError: Throwable? = null
        while (port < preferredPort + 20) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName(ip), port), 8)
                serverSocket = socket
                val token = LocalNetwork.token()
                val expiresAt = System.currentTimeMillis() + TOKEN_TTL_MS
                val created = Session(
                    token = token,
                    pairingCode = LocalNetwork.pairingCode(),
                    ip = ip,
                    port = port,
                    serviceUrl = LocalNetwork.serviceUrl(ip, port),
                    importUrl = LocalNetwork.tokenUrl(ip, port, token),
                    expiresAt = expiresAt,
                )
                session = created
                acceptThread = Thread({ acceptLoop(socket) }, "lan-import-accept").apply {
                    isDaemon = true
                    start()
                }
                scheduler.schedule({ close("token 过期") }, TOKEN_TTL_MS, TimeUnit.MILLISECONDS)
                TvLog.i(TAG, "局域网导入服务启动：${created.serviceUrl}（token 5 分钟有效）")
                return created
            } catch (t: Throwable) {
                lastError = t
                port++
            }
        }
        TvLog.e(TAG, "启动失败：8088..${preferredPort + 19} 均不可用", lastError)
        return null
    }

    fun close(reason: String = "主动关闭") {
        if (stopped) return
        stopped = true
        TvLog.i(TAG, "关闭局域网导入服务：$reason")
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { workers.shutdownNow() }
        runCatching { scheduler.shutdownNow() }
    }

    // ---------------------------------------------------------------- 监听

    private fun acceptLoop(socket: ServerSocket) {
        while (!stopped) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (stopped) break else continue
            }
            if (requestCount.incrementAndGet() > MAX_REQUESTS) {
                close("请求过多")
                break
            }
            execute { handle(client) }
        }
    }

    private fun execute(block: () -> Unit) {
        try {
            workers.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    TvLog.w(TAG, "请求处理异常：${t.message}")
                }
            }
        } catch (e: Throwable) {
            TvLog.w(TAG, "线程池已关闭", e)
        }
    }

    // ---------------------------------------------------------------- 请求

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = SOCKET_TIMEOUT_MS
            val remote = s.inetAddress
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())

            val requestLine = readAsciiLine(input, 8192) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respondJson(output, 400, errorBody("E_REQUEST", "请求格式错误"))
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val headers = readHeaders(input)
            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

            if (!LocalNetwork.isLanClient(remote)) {
                respondJson(output, 403, errorBody("E_FORBIDDEN", "只允许局域网设备访问"))
                return
            }
            if (contentLength > MAX_BODY_BYTES) {
                respondJson(output, 413, errorBody(SourceIssueCodes.SIZE, "请求体超过 2MB"))
                return
            }
            val body = if (contentLength > 0) readBody(input, contentLength.toInt()) else ByteArray(0)

            try {
                when {
                    method == "GET" && (path == "/" || path == "/index.html") ->
                        respondHtml(output, 200, page(tokenOrNull = null))
                    method == "GET" && path.startsWith("/i/") -> {
                        val token = path.removePrefix("/i/")
                        if (validateToken(token)) {
                            respondHtml(output, 200, page(tokenOrNull = token))
                        } else {
                            respondHtml(output, 410, expiredPage())
                        }
                    }
                    method == "GET" && path == "/api/ping" ->
                        respondJson(output, 200, mapOf("ok" to true, "app" to "tvbox", "version" to 1))
                    method == "POST" && path == "/api/pair" -> handlePair(output, headers, body)
                    method == "POST" && path == "/api/import" -> handleImport(output, headers, body)
                    method == "GET" && path == "/favicon.ico" -> respondBytes(output, 204, "text/plain", ByteArray(0))
                    else -> respondJson(output, 404, errorBody("E_NOT_FOUND", "接口不存在"))
                }
            } catch (t: Throwable) {
                TvLog.w(TAG, "处理 $method $path 失败：${t.message}")
                runCatching { respondJson(output, 500, errorBody("E_SERVER", "服务内部错误")) }
            } finally {
                runCatching { output.flush() }
            }
        }
    }

    private fun handlePair(output: OutputStream, headers: Map<String, String>, body: ByteArray) {
        val s = session ?: run {
            respondJson(output, 410, errorBody("E_EXPIRED", "导入服务已关闭"))
            return
        }
        if (System.currentTimeMillis() > s.expiresAt || used.get()) {
            respondJson(output, 410, errorBody("E_EXPIRED", "导入码已过期，请在电视上重新打开导入页"))
            return
        }
        if (!isJson(headers)) {
            respondJson(output, 415, errorBody("E_BODY", "Content-Type 必须是 application/json"))
            return
        }
        val code = try {
            JSONObject(String(body, Charsets.UTF_8)).optString("code", "")
        } catch (e: Exception) {
            ""
        }
        if (code.trim() != s.pairingCode) {
            val failures = failedAttempts.incrementAndGet()
            if (failures >= MAX_FAILED_ATTEMPTS) close("配对码错误次数过多")
            respondJson(output, 403, errorBody("E_PAIR", "配对码不正确，请核对电视上显示的 6 位数字"))
            return
        }
        respondJson(
            output,
            200,
            mapOf(
                "ok" to true,
                "token" to s.token,
                "expiresInSec" to ((s.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0),
                "maxBytes" to MAX_FILE_BYTES,
            ),
        )
    }

    private fun handleImport(output: OutputStream, headers: Map<String, String>, body: ByteArray) {
        val s = session ?: run {
            respondJson(output, 410, errorBody("E_EXPIRED", "导入服务已关闭"))
            return
        }
        if (System.currentTimeMillis() > s.expiresAt) {
            close("token 过期")
            respondJson(output, 410, errorBody("E_EXPIRED", "导入码已过期，请在电视上重新打开导入页"))
            return
        }
        if (used.get()) {
            respondJson(output, 410, errorBody("E_EXPIRED", "该导入码已使用过，请回到电视重新生成"))
            return
        }
        val contentType = headers["content-type"] ?: ""
        val multipart = contentType.lowercase().startsWith("multipart/form-data")
        val json = contentType.lowercase().startsWith("application/json") ||
            contentType.lowercase().startsWith("text/plain")
        if (!multipart && !json) {
            respondJson(output, 415, errorBody("E_BODY", "只接受 application/json 或 multipart/form-data"))
            return
        }
        if (!importing.compareAndSet(false, true)) {
            respondJson(output, 429, errorBody("E_BUSY", "正在导入，请稍候"))
            return
        }
        try {
            val parsed = parseSubmission(headers, body, multipart)
            if (parsed == null) {
                respondJson(output, 400, errorBody("E_REQUEST", "缺少 token 或导入内容"))
                return
            }
            if (!validateToken(parsed.token)) {
                respondJson(output, 410, errorBody("E_EXPIRED", "导入码无效或已过期，请回到电视重新生成"))
                return
            }
            val report = try {
                executor(parsed.submission)
            } catch (t: Throwable) {
                TvLog.w(TAG, "导入执行失败：${t.message}")
                ImportReport.failure(ImportOrigin.LAN, "E_SERVER", "服务内部错误：${t.message}")
            }
            if (report.ok) {
                used.set(true)
                respondJson(output, 200, report.toWire())
                onReport(report)
                if (stopAfterSuccess) {
                    scheduler.schedule({ close("导入成功") }, 350, TimeUnit.MILLISECONDS)
                }
            } else {
                val failures = failedAttempts.incrementAndGet()
                if (failures >= MAX_FAILED_ATTEMPTS) close("导入失败次数过多")
                respondJson(output, 200, report.toWire())
            }
        } finally {
            importing.set(false)
        }
    }

    private data class ParsedSubmission(val token: String, val submission: Submission)

    private fun parseSubmission(
        headers: Map<String, String>,
        body: ByteArray,
        multipart: Boolean,
    ): ParsedSubmission? {
        if (multipart) {
            val boundary = MultipartForm.boundary(headers["content-type"]) ?: return null
            val parts = MultipartForm.parse(body, boundary)
            val token = MultipartForm.field(parts, "token")?.trim().orEmpty()
            if (token.isEmpty()) return null
            val file = MultipartForm.file(parts)
            if (file != null) {
                if (file.bytes.size > MAX_FILE_BYTES) return null
                return ParsedSubmission(token, Submission(bytes = file.bytes, filename = file.filename))
            }
            val content = MultipartForm.field(parts, "payload")
            if (!content.isNullOrBlank()) return ParsedSubmission(token, Submission(text = content))
            val url = MultipartForm.field(parts, "url")
            if (!url.isNullOrBlank()) return ParsedSubmission(token, Submission(url = url))
            return null
        }
        val obj = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            return null
        }
        val token = obj.optString("token", "").trim()
        if (token.isEmpty()) return null
        val url = obj.optString("url", "").trim()
        if (url.isNotEmpty()) return ParsedSubmission(token, Submission(url = url))
        val payload = obj.optString("payload", "")
        if (payload.isNotBlank()) return ParsedSubmission(token, Submission(text = payload))
        return null
    }

    private fun validateToken(token: String?): Boolean {
        val s = session ?: return false
        if (token.isNullOrEmpty() || used.get()) return false
        if (System.currentTimeMillis() > s.expiresAt) return false
        return constantTimeEquals(token, s.token)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    // ---------------------------------------------------------------- 页面

    private fun page(tokenOrNull: String?): String {
        val s = session ?: return expiredPage()
        val tokenJson = if (tokenOrNull == null) "null" else "\"" + tokenOrNull + "\""
        // 安全：配对码只在“已带 token 的页面”里注入；`/` 页面对整个局域网可见，不能泄露配对码。
        val pairing = if (tokenOrNull == null) "" else s.pairingCode
        return pageTemplate
            .replace("__TOKEN_JSON__", tokenJson)
            .replace("__PAIRING__", pairing)
            .replace("__SERVICE__", s.serviceUrl)
            .replace("__EXPIRE_MIN__", (TOKEN_TTL_MS / 60000).toString())
            .replace("__MAX_MB__", (MAX_FILE_BYTES / 1024 / 1024).toString())
    }

    private fun expiredPage(): String = """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>导入码已失效</title></head>
        <body style="font-family:sans-serif;padding:24px;background:#111;color:#eee">
        <h2>导入码已失效</h2>
        <p>请在电视上重新打开「导入源」页面，扫码或输入新的 6 位配对码。</p>
        </body></html>
    """.trimIndent()

    // ---------------------------------------------------------------- HTTP 工具

    private fun readAsciiLine(input: InputStream, limit: Int): String? {
        val out = ByteArrayOutputStream(128)
        var b = input.read()
        if (b < 0) return null
        while (b >= 0) {
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                out.write(b)
                if (out.size() > limit) return null
            }
            b = input.read()
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val map = HashMap<String, String>()
        var count = 0
        while (count++ < 50) {
            val line = readAsciiLine(input, 8192) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            map[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return map
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(length, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size, remaining))
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun isJson(headers: Map<String, String>): Boolean {
        val ct = headers["content-type"]?.lowercase() ?: return false
        return ct.startsWith("application/json") || ct.startsWith("text/plain")
    }

    private fun errorBody(code: String, message: String): Map<String, Any?> =
        mapOf("ok" to false, "error" to mapOf("code" to code, "message" to message), "summary" to message)

    private fun respondJson(output: OutputStream, status: Int, body: Map<String, Any?>) =
        respondBytes(output, status, "application/json; charset=utf-8", JSONObject(body).toString().toByteArray(Charsets.UTF_8))

    private fun respondHtml(output: OutputStream, status: Int, html: String) =
        respondBytes(output, status, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))

    private fun respondBytes(output: OutputStream, status: Int, contentType: String, payload: ByteArray) {
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        410 -> "Gone"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        429 -> "Too Many Requests"
        else -> "Internal Server Error"
    }

    private companion object {
        const val TAG = "LanImport"
        const val DEFAULT_PORT = 8088
        const val TOKEN_TTL_MS = 5 * 60 * 1000L
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_BODY_BYTES = MAX_FILE_BYTES + 256 * 1024
        const val MAX_REQUESTS = 60
        const val MAX_FAILED_ATTEMPTS = 5
        const val SOCKET_TIMEOUT_MS = 15000
    }
}
