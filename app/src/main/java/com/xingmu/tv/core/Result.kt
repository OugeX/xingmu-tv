package com.xingmu.tv.core

/**
 * 全工程唯一的“成功/失败”结果类型（替代崩溃式异常传播，也不使用 kotlin.Result 以免混淆）。
 *
 * 约定：
 * - 数据层 / 播放器层的 public suspend 函数统一返回 `Result<T>`；
 * - UI 只处理 [Ok] / [Err] 两种分支，不 catch 异常；
 * - 错误必须携带稳定的 [ErrorCodes]，供 UI 显示与日志检索。
 *
 * 用法：
 * ```
 * when (val r = repo.getDetail(vodId, sourceId)) {
 *     is Result.Ok -> render(r.value)
 *     is Result.Err -> showError(r.error)
 * }
 * ```
 */
sealed interface Result<out T> {

    val isOk: Boolean get() = this is Ok

    val isErr: Boolean get() = this is Err

    data class Ok<T>(val value: T) : Result<T>

    data class Err(val error: AppError) : Result<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value

    fun errorOrNull(): AppError? = (this as? Err)?.error

    companion object {
        fun <T> success(value: T): Result<T> = Ok(value)

        fun failure(error: AppError): Result<Nothing> = Err(error)

        fun <T> failure(code: String, msg: String): Result<T> = Err(AppError(code, msg))
    }
}

/** 语义化别名：`AppResult<VodDetail>` 比裸 `Result<VodDetail>` 更不容易和 kotlin.Result 混读。 */
typealias AppResult<T> = Result<T>

inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Ok -> Result.Ok(transform(value))
    is Result.Err -> this
}

inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> = when (this) {
    is Result.Ok -> transform(value)
    is Result.Err -> this
}

inline fun <T> Result<T>.onOk(action: (T) -> Unit): Result<T> {
    if (this is Result.Ok) action(value)
    return this
}

inline fun <T> Result<T>.onErr(action: (AppError) -> Unit): Result<T> {
    if (this is Result.Err) action(error)
    return this
}

fun <T> Result<T>.getOrElse(fallback: (AppError) -> T): T = when (this) {
    is Result.Ok -> value
    is Result.Err -> fallback(error)
}

fun <T> Result<T>.getOrThrow(): T = when (this) {
    is Result.Ok -> value
    is Result.Err -> throw AppException(error)
}
