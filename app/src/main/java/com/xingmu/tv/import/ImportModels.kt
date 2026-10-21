package com.xingmu.tv.import

import com.xingmu.tv.source.ParsedSource
import com.xingmu.tv.source.ProbeOutcome
import com.xingmu.tv.source.RejectedSource
import com.xingmu.tv.source.SourceIssueCodes
import com.xingmu.tv.source.SourceWarning

/** 导入来源（用于埋点/提示文案；不参与校验）。 */
enum class ImportOrigin {
    PASTE, FILE, URL, USB, SAF, LAN, BUILTIN;

    val label: String
        get() = when (this) {
            PASTE -> "粘贴 JSON"
            FILE -> "上传文件"
            URL -> "地址导入"
            USB -> "U 盘"
            SAF -> "文件选择器"
            LAN -> "手机扫码"
            BUILTIN -> "内置源"
        }
}

/**
 * 导入报告（对齐 docs/PLAN_SOURCES.md §4.4 ImportReport）。
 *
 * [accepted] 里是真正写入源表的源；[rejected] 逐条给出 code + 中文原因，UI 直接展示。
 */
data class ImportReport(
    val origin: ImportOrigin,
    val accepted: List<ParsedSource> = emptyList(),
    val rejected: List<RejectedSource> = emptyList(),
    val warnings: List<SourceWarning> = emptyList(),
    val probes: Map<String, ProbeOutcome> = emptyMap(),
    val dedupeReused: List<Pair<String, String>> = emptyList(),
    val documentError: RejectedSource? = null,
    val durationMs: Long = 0L,
    /** 本次导入新增/更新的源数（幂等重导时可能小于 accepted.size）。 */
    val persisted: Int = 0,
) {
    val ok: Boolean get() = documentError == null && accepted.isNotEmpty()

    val channelCount: Int
        get() = probes.values.sumOf { it.channelCount ?: 0 }

    /** H5 / TV 共用的一句结果文案。 */
    fun summaryText(): String = when {
        documentError != null -> "导入失败：${documentError.message}"
        accepted.isEmpty() -> "导入失败：${rejected.firstOrNull()?.message ?: "没有可导入的源"}"
        else -> "已导入 ${accepted.size} 个源" + if (channelCount > 0) "（$channelCount 个频道）" else ""
    }

    /** 第一条错误的中文描述（含错误码），给遥控器兜底提示用。 */
    fun firstErrorText(): String? {
        val issue = documentError ?: rejected.firstOrNull() ?: return null
        return "[${issue.code}] ${issue.message}"
    }

    fun toWire(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["ok"] = ok
        out["summary"] = summaryText()
        out["accepted"] = accepted.size
        out["persisted"] = persisted
        out["channels"] = channelCount
        out["warnings"] = warnings.map { mapOf("code" to it.code, "message" to it.message) }
        out["rejected"] = rejected.map {
            mapOf("index" to it.index, "code" to it.code, "message" to it.message, "field" to it.field)
        }
        out["probes"] = probes.values.map {
            mapOf(
                "sourceId" to it.sourceId,
                "state" to it.state.name,
                "latencyMs" to it.latencyMs,
                "classes" to it.classCount,
                "channels" to it.channelCount,
                "message" to it.message,
            )
        }
        out["error"] = documentError?.let { mapOf("code" to it.code, "message" to it.message) }
        out["durationMs"] = durationMs
        if (documentError == null && rejected.isNotEmpty() && accepted.isEmpty()) {
            out["summary"] = summaryText()
        }
        return out
    }

    companion object {
        fun failure(origin: ImportOrigin, code: String, message: String, durationMs: Long = 0L): ImportReport =
            ImportReport(
                origin = origin,
                documentError = RejectedSource(0, code, message),
                durationMs = durationMs,
            )

        fun rejected(origin: ImportOrigin, items: List<RejectedSource>, durationMs: Long = 0L): ImportReport =
            ImportReport(origin = origin, rejected = items, durationMs = durationMs)
    }
}

/** 供日志使用：一次导入的短摘要。 */
fun ImportReport.logLine(): String =
    "origin=$origin ok=$ok accepted=${accepted.size} rejected=${rejected.size} " +
        "warn=${warnings.size} persisted=$persisted duration=${durationMs}ms" +
        (documentError?.let { " docErr=${it.code}" } ?: "")

/**
 * 错误码常量转发（import 层只读引用，避免两处定义漂移）。
 * 真正的定义在 source/SourceIssueCodes.kt，与 tools/validate_sources.py 对齐。
 */
object ImportCodes {
    /** TV 端配对码校验（本地 UI 用，不属于源文件校验码表）。 */
    const val PAIR = "E_PAIR"
    const val PAIR_EXPIRED = "E_PAIR_EXPIRED"

    val JSON get() = SourceIssueCodes.JSON
    val SIZE get() = SourceIssueCodes.SIZE
    val ENCODING get() = SourceIssueCodes.ENCODING
    val SCHEMA get() = SourceIssueCodes.SCHEMA
    val KIND get() = SourceIssueCodes.KIND
    val ID get() = SourceIssueCodes.ID
    val NAME get() = SourceIssueCodes.NAME
    val URL get() = SourceIssueCodes.URL
    val FIELD get() = SourceIssueCodes.FIELD
    val REGEX get() = SourceIssueCodes.REGEX
    val LIMIT get() = SourceIssueCodes.LIMIT
    val NESTED get() = SourceIssueCodes.NESTED
    val SIGNATURE get() = SourceIssueCodes.SIGNATURE
    val ADBLOCK get() = SourceIssueCodes.ADBLOCK
    val CHANNEL get() = SourceIssueCodes.CHANNEL
    val EPG get() = SourceIssueCodes.EPG
}
