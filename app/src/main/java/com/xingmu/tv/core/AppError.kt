package com.xingmu.tv.core

import androidx.compose.runtime.Immutable

/**
 * 全局错误模型：所有网络 / 解析 / 播放错误都要转成 [AppError]，UI 只展示错误码 + 中文原因，
 * 不允许把异常直接抛到主线程（见 docs/PLAN_ARCHITECTURE.md §3.4）。
 *
 * @param code 稳定错误码，使用 [ErrorCodes] 常量或 `E_` 前缀的字符串。
 * @param msg  面向用户的中文原因（不含堆栈）。
 * @param cause 原始异常，仅用于日志，不参与 UI 展示。
 */
@Immutable
data class AppError(
    val code: String,
    val msg: String,
    val cause: Throwable? = null,
) {
    fun display(): String = if (code.isBlank()) msg else "[$code] $msg"
}

/** 与 `tools/validate_sources.py` 的错误码风格保持一致（E_ 前缀、大写下划线）。 */
object ErrorCodes {
    const val NETWORK = "E_NETWORK"
    const val TIMEOUT = "E_TIMEOUT"
    const val HTTP = "E_HTTP"
    const val PARSE = "E_PARSE"
    const val SOURCE_INVALID = "E_SOURCE_INVALID"
    const val SOURCE_OFFLINE = "E_SOURCE_OFFLINE"
    const val NO_SOURCE = "E_NO_SOURCE"
    const val NO_PLAY_URL = "E_NO_PLAY_URL"
    const val PLAYBACK = "E_PLAYBACK"
    const val UNSUPPORTED_FORMAT = "E_UNSUPPORTED_FORMAT"
    const val IO = "E_IO"
    const val UNKNOWN = "E_UNKNOWN"
}

fun Throwable.toAppError(code: String = ErrorCodes.UNKNOWN, msg: String? = null): AppError =
    AppError(code = code, msg = msg ?: (message ?: this::class.java.simpleName), cause = this)

@Immutable
class AppException(val error: AppError) : Exception(error.msg, error.cause)
