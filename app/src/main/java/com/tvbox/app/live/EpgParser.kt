package com.tvbox.app.live

import android.util.Xml
import com.tvbox.app.core.TvLog
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * XMLTV（`<tv><channel/><programme/>`）流式解析（PLAN_SOURCES §3.5.1）。
 *
 * - 按魔数 `1f 8b` 判定 gzip（不看扩展名），再包 GZIPInputStream；
 * - 解压后 ≤ 8MB（[MAX_BYTES]），超限截断不 OOM；
 * - `XmlPullParser` 流式，不建 DOM；
 * - 只保留 [now-2h, now+2h] 窗口内的节目，按 channel 建索引，供「正在播 / 下一档」查询。
 *
 * 失败静默：任何异常都返回已收集部分，不影响频道列表与播放。
 */
object EpgParser {

    const val MAX_BYTES: Long = 8L * 1024 * 1024
    const val MAX_PROGRAMS = 6_000
    private const val MAX_TEXT = 120
    private const val WINDOW_BEHIND_MS = 2L * 60 * 60 * 1000
    private const val WINDOW_AHEAD_MS = 2L * 60 * 60 * 1000

    private val formatsWithTz = listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmmssZ")
    private const val PATTERN_PLAIN = "yyyyMMddHHmmss"

    fun parse(input: InputStream, nowMs: Long): List<EpgProgram> {
        val result = ArrayList<EpgProgram>(512)
        val dateFormats = (formatsWithTz.map { SimpleDateFormat(it, Locale.US) } +
            SimpleDateFormat(PATTERN_PLAIN, Locale.US))
        try {
            val stream = maybeGunzip(input)
            val limited = LimitedInputStream(stream, MAX_BYTES)
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(limited, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG &&
                    parser.name?.equals("programme", ignoreCase = true) == true
                ) {
                    val program = readProgramme(parser, nowMs, dateFormats)
                    if (program != null) {
                        result += program
                        if (result.size >= MAX_PROGRAMS) break
                    }
                }
                event = parser.next()
            }
        } catch (t: Throwable) {
            // 截断/坏 XML 都可能是正常情况（大文件超限），保留已解析部分
            TvLog.w(TAG, "epg parse stopped after ${result.size} programs: ${t.message}")
        }
        TvLog.i(TAG, "epg parsed ${result.size} programs (now=$nowMs)")
        return result
    }

    private fun readProgramme(
        parser: XmlPullParser,
        nowMs: Long,
        formats: List<SimpleDateFormat>,
    ): EpgProgram? {
        val channelId = parser.getAttributeValue(null, "channel")?.trim().orEmpty()
        if (channelId.isEmpty()) return null
        val start = parseTime(parser.getAttributeValue(null, "start"), formats)
        val stop = parseTime(parser.getAttributeValue(null, "stop"), formats)
        if (start == null || stop == null || stop <= start) return null

        var title: String? = null
        var desc: String? = null
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG) {
                when (parser.name?.lowercase(Locale.US)) {
                    "title" -> if (title == null) title = parser.nextText()?.trim()
                    "desc" -> if (desc == null) desc = parser.nextText()?.trim()
                }
            }
        }
        if (title.isNullOrBlank()) return null

        // 只保留“正在播 / 下一档”附近，避免 8MB XMLTV 常驻内存
        if (stop <= nowMs - WINDOW_BEHIND_MS || start >= nowMs + WINDOW_AHEAD_MS) return null

        return EpgProgram(
            channelId = channelId.lowercase(),
            title = title.take(MAX_TEXT),
            startMs = start,
            stopMs = stop,
            desc = desc?.take(MAX_TEXT),
        )
    }

    private fun parseTime(raw: String?, formats: List<SimpleDateFormat>): Long? {
        val value = raw?.trim().orEmpty()
        if (value.length < 14) return null
        // 兼容 "20261021190000 +0800" / "+08:00" / 无时区
        val normalized = if (value.length > 14) {
            value.substring(0, 14) + " " + value.substring(14).trim().replace(":", "")
        } else {
            value
        }
        for (format in formats) {
            try {
                return format.parse(normalized)?.time
            } catch (t: Throwable) {
                // try next pattern
            }
        }
        return null
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 16 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) {
            TvLog.d(TAG, "gzip magic detected -> GZIPInputStream")
            GZIPInputStream(buffered, 32 * 1024)
        } else {
            buffered
        }
    }

    /** 解压后最大字节数，超过后返回 EOF（截断而不是 OOM）。 */
    private class LimitedInputStream(
        private val delegate: InputStream,
        private val limit: Long,
    ) : InputStream() {
        private var count = 0L

        override fun read(): Int {
            if (count >= limit) return -1
            val value = delegate.read()
            if (value >= 0) count++
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (count >= limit || len <= 0) return -1
            val allowed = minOf(len.toLong(), limit - count).toInt()
            val read = delegate.read(b, off, allowed)
            if (read > 0) count += read
            return read
        }

        override fun close() = delegate.close()
    }

    private const val TAG = "Epg"
}

/** 解析结果索引：channelId → 节目（按 start 升序）。 */
class EpgIndex(
    private val byChannel: Map<String, List<EpgProgram>>,
    val updatedAtMs: Long,
) {
    val programCount: Int = byChannel.values.sumOf { it.size }

    fun isAvailable(channel: LiveChannel): Boolean =
        channel.epgKeys.any { byChannel.containsKey(it) }

    fun nowNext(channel: LiveChannel, nowMs: Long = System.currentTimeMillis()): EpgNowNext {
        for (key in channel.epgKeys) {
            val programs = byChannel[key] ?: continue
            return pick(programs, key, nowMs)
        }
        return EpgNowNext.EMPTY
    }

    fun nowNext(keys: Collection<String>, nowMs: Long): EpgNowNext {
        for (key in keys) {
            val programs = byChannel[key.lowercase()] ?: continue
            return pick(programs, key, nowMs)
        }
        return EpgNowNext.EMPTY
    }

    private fun pick(programs: List<EpgProgram>, channelId: String, nowMs: Long): EpgNowNext {
        var now: EpgProgram? = null
        var next: EpgProgram? = null
        for (program in programs) {
            if (program.stopMs <= nowMs) continue
            if (program.startMs <= nowMs) {
                now = program
            } else {
                next = program
                break
            }
        }
        return EpgNowNext(
            channelId = channelId,
            now = now,
            next = next,
            updatedAtMs = updatedAtMs,
        )
    }

    companion object {
        val EMPTY = EpgIndex(emptyMap(), 0L)
    }
}
