package com.tvbox.desktop.core

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 极简日志：桌面端不引日志框架，stdout 足够，必要时由 Main 额外挂文件输出。
 */
object Log {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    @Volatile
    var fileSink: ((String) -> Unit)? = null

    @Volatile
    var verbose: Boolean = false

    fun i(tag: String, message: String) = write("I", tag, message)

    fun d(tag: String, message: String) {
        if (verbose) write("D", tag, message)
    }

    fun w(tag: String, message: String) = write("W", tag, message)

    fun e(tag: String, message: String, error: Throwable? = null) {
        write("E", tag, if (error == null) message else "$message :: ${error.message ?: error.javaClass.name}")
        error?.stackTrace?.take(6)?.forEach { frame -> write("E", tag, "    at $frame") }
    }

    private fun write(level: String, tag: String, message: String) {
        val line = "${LocalTime.now().format(timeFormat)} $level/$tag $message"
        println(line)
        fileSink?.invoke(line)
    }
}
