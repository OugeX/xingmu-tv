package com.xingmu.tv.import

/**
 * 极简 multipart/form-data 解析（只服务于局域网 H5 的 ≤2MB 单文件上传）。
 *
 * 为什么不用 OkHttp 的 MultipartBody：TV 端是服务端，OkHttp 没有服务端解析器；
 * 目的单一（一个 file 字段 + 少量文本字段），手写 100 行比引依赖更省包体。
 */
object MultipartForm {

    data class Part(val name: String, val filename: String?, val contentType: String?, val bytes: ByteArray)

    private val CRLF = byteArrayOf(13, 10)
    private val HEADER_END = byteArrayOf(13, 10, 13, 10)

    fun boundary(contentType: String?): String? {
        if (contentType == null) return null
        val marker = "boundary="
        val idx = contentType.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        var value = contentType.substring(idx + marker.length).trim()
        if (value.startsWith("\"")) {
            val end = value.indexOf('"', 1)
            value = if (end > 0) value.substring(1, end) else value.substring(1)
        } else {
            value = value.substringBefore(';').trim()
        }
        return value.ifBlank { null }
    }

    fun parse(body: ByteArray, boundary: String, maxParts: Int = 8): List<Part> {
        val delim = "--$boundary".toByteArray(Charsets.UTF_8)
        val nextDelim = ("\r\n--$boundary").toByteArray(Charsets.UTF_8)
        val parts = ArrayList<Part>()
        var pos = indexOf(body, delim, 0) ?: return parts
        pos += delim.size
        while (parts.size < maxParts) {
            if (pos + 1 < body.size && body[pos] == '-'.code.toByte() && body[pos + 1] == '-'.code.toByte()) break
            if (pos + 1 < body.size && body[pos] == CRLF[0] && body[pos + 1] == CRLF[1]) pos += 2
            val headerEnd = indexOf(body, HEADER_END, pos) ?: break
            val headerText = String(body, pos, headerEnd - pos, Charsets.UTF_8)
            val contentStart = headerEnd + HEADER_END.size
            val next = indexOf(body, nextDelim, contentStart) ?: body.size
            if (next < contentStart) break
            var name = ""
            var filename: String? = null
            var contentType: String? = null
            for (line in headerText.split("\r\n")) {
                val lower = line.lowercase()
                when {
                    lower.startsWith("content-disposition:") -> {
                        name = valueOf(line, "name") ?: ""
                        filename = valueOf(line, "filename")
                    }
                    lower.startsWith("content-type:") -> contentType = line.substringAfter(':').trim()
                }
            }
            parts.add(Part(name, filename, contentType, body.copyOfRange(contentStart, next)))
            pos = next + nextDelim.size
        }
        return parts
    }

    fun field(parts: List<Part>, name: String): String? =
        parts.firstOrNull { it.name == name && it.filename == null }?.let { String(it.bytes, Charsets.UTF_8) }

    fun file(parts: List<Part>, name: String = "file"): Part? =
        parts.firstOrNull { it.name == name && it.filename != null } ?: parts.firstOrNull { it.filename != null }

    private fun valueOf(headerLine: String, key: String): String? {
        val marker = "$key="
        val idx = headerLine.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        var value = headerLine.substring(idx + marker.length).trim()
        if (value.startsWith("\"")) {
            val end = value.indexOf('"', 1)
            value = if (end > 0) value.substring(1, end) else value.substring(1)
        } else {
            value = value.substringBefore(';').trim()
        }
        return value
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
        if (needle.isEmpty() || haystack.size < needle.size) return null
        val last = haystack.size - needle.size
        var i = from.coerceAtLeast(0)
        outer@ while (i <= last) {
            if (haystack[i] == needle[0]) {
                var j = 1
                while (j < needle.size) {
                    if (haystack[i + j] != needle[j]) {
                        i++
                        continue@outer
                    }
                    j++
                }
                return i
            }
            i++
        }
        return null
    }
}
