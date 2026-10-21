package com.xingmu.tv.source

/**
 * 导入错误码（与 tools/validate_sources.py / docs/PLAN_SOURCES.md §4.3 完全一致）。
 * 中文文案可直接展示给用户（UI 不需要再拼错误说明）。
 */
object SourceIssueCodes {
    const val JSON = "E_JSON"
    const val SIZE = "E_SIZE"
    const val ENCODING = "E_ENCODING"
    const val SCHEMA = "E_SCHEMA"
    const val KIND = "E_KIND"
    const val ID = "E_ID"
    const val NAME = "E_NAME"
    const val URL = "E_URL"
    const val FIELD = "E_FIELD"
    const val REGEX = "E_REGEX"
    const val LIMIT = "E_LIMIT"
    const val NESTED = "E_NESTED"
    const val SIGNATURE = "E_SIGNATURE"
    const val ADBLOCK = "E_ADBLOCK"
    const val CHANNEL = "E_CHANNEL"
    const val EPG = "E_EPG"

    const val W_HTTP = "W_HTTP"
    const val W_UNVERIFIED = "W_UNVERIFIED"
    const val W_DUPLICATE_ENDPOINT = "W_DUPLICATE_ENDPOINT"
    const val W_URL_NORMALIZED = "W_URL_NORMALIZED"
    const val W_CREDENTIALS = "W_CREDENTIALS"
    const val W_SIGNATURE_UNVERIFIED = "W_SIGNATURE_UNVERIFIED"
    const val W_CLASS_FALLBACK = "W_CLASS_FALLBACK"

    /** 错误码 -> 中文摘要（用于 UI 的“原因”列）。 */
    fun summary(code: String): String = when (code) {
        JSON -> "JSON 格式错误"
        SIZE -> "文件超过大小上限"
        ENCODING -> "文件编码无法识别（仅支持 UTF-8 / GB18030）"
        SCHEMA -> "协议版本不支持"
        KIND -> "源类型不支持"
        ID -> "源 id 非法或重复"
        NAME -> "源名称非法"
        URL -> "地址非法"
        FIELD -> "字段类型/范围错误"
        REGEX -> "过滤正则无效"
        LIMIT -> "数量超过上限"
        NESTED -> "合集不能包含合集"
        SIGNATURE -> "签名格式非法"
        ADBLOCK -> "广告拦截规则非法或会拦截源自身"
        CHANNEL -> "频道定义非法"
        EPG -> "EPG 地址非法"
        else -> "未知问题"
    }
}

/** 一条校验问题（code + 中文 message + 可选字段路径）。 */
data class SourceIssue(
    val code: String,
    val message: String,
    val field: String? = null,
)

/** 被拒绝的条目（index 对应该文件内的源序号，bundle 内逐条）。 */
data class RejectedSource(
    val index: Int,
    val code: String,
    val message: String,
    val field: String? = null,
)

/** 警告（不阻断导入）。 */
data class SourceWarning(
    val code: String,
    val message: String,
    val sourceId: String? = null,
)

data class ParseOptions(
    /** L0 文件上限：本地 8MB / 局域网 2MB（docs/PLAN_SOURCES.md §4.2、SOURCE_IMPORT_SPEC §2）。 */
    val maxBytes: Int = 8 * 1024 * 1024,
    /** 允许内网/回环地址（用户显式勾选“允许局域网源”）。 */
    val allowLan: Boolean = false,
    /** 允许内部字段（仅用于回读 SQLite 里的 config_json，用户导入文件仍严格拒绝）。 */
    val allowInternalFields: Boolean = false,
)

data class ValidationResult(
    val accepted: List<ParsedSource>,
    val rejected: List<RejectedSource>,
    val warnings: List<SourceWarning>,
    val documentError: RejectedSource? = null,
    val bundle: ParsedBundle? = null,
) {
    val ok: Boolean get() = documentError == null && accepted.isNotEmpty()
}

/** L0 解码结果。 */
data class DecodedText(
    val text: String?,
    val issue: SourceIssue?,
    val warnings: List<SourceWarning> = emptyList(),
)
