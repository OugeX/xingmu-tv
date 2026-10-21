package com.xingmu.tv.import

import com.xingmu.tv.core.AppDispatchers
import com.xingmu.tv.core.TvLog
import com.xingmu.tv.source.ParseOptions
import com.xingmu.tv.source.ParsedSource
import com.xingmu.tv.source.ProbeOutcome
import com.xingmu.tv.source.ProbeState
import com.xingmu.tv.source.RejectedSource
import com.xingmu.tv.source.SourceIssueCodes
import com.xingmu.tv.source.SourceProbe
import com.xingmu.tv.source.SourceRepositoryImpl
import com.xingmu.tv.source.SourceSpecParser
import com.xingmu.tv.source.SourceWarning
import com.xingmu.tv.source.ValidationResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class ImportOptions(
    val origin: ImportOrigin = ImportOrigin.PASTE,
    /** L0 上限：本地 8MB / 局域网 2MB。 */
    val maxBytes: Int = DEFAULT_MAX_BYTES,
    val allowLan: Boolean = false,
    val filename: String? = null,
    /** 是否做 L3/L4 网络探测（离线导入可关；默认开，失败不阻断）。 */
    val probe: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_BYTES = 8 * 1024 * 1024
        const val LAN_MAX_BYTES = 2 * 1024 * 1024
    }
}

/**
 * 导入管线编排：L0 解码 → 规范化 → L1/L2 校验 → L3/L4 探测 → 落库 → 报告。
 *
 * 与 SourceSpecParser（L0-L2）分工：本类只做输入类型识别、网络探测、持久化与报告组装。
 * 所有入口最终都汇聚到 [importText]，保证「手机扫码 / U 盘 / SAF / 遥控器 / 上传文件」同一条管线。
 */
class ImportPipeline(private val repository: SourceRepositoryImpl) {

    private val tag = "ImportPipeline"

    suspend fun importBytes(bytes: ByteArray, options: ImportOptions): ImportReport = withContext(AppDispatchers.Io) {
        val started = System.currentTimeMillis()
        val decoded = SourceSpecParser.decodeText(bytes, options.maxBytes)
        if (decoded.text == null) {
            val issue = decoded.issue!!
            return@withContext ImportReport.failure(
                options.origin, issue.code, issue.message, System.currentTimeMillis() - started,
            )
        }
        val textReport = importText(decoded.text, options, started)
        if (decoded.warnings.isEmpty()) {
            textReport
        } else {
            textReport.copy(warnings = (decoded.warnings + textReport.warnings).distinctBy { it.code + it.message })
        }
    }

    suspend fun importText(
        text: String,
        options: ImportOptions,
        startedAt: Long = System.currentTimeMillis(),
    ): ImportReport = withContext(AppDispatchers.Io) {
        val started = startedAt
        if (text.length > options.maxBytes) {
            return@withContext ImportReport.failure(
                options.origin,
                SourceIssueCodes.SIZE,
                "内容超过上限（${options.maxBytes / 1024 / 1024} MB）",
                System.currentTimeMillis() - started,
            )
        }
        val normalized = ImportNormalizer.fromText(text, options.filename)
        if (normalized.json == null) {
            val issue = normalized.issue!!
            return@withContext ImportReport.failure(options.origin, issue.code, issue.message, System.currentTimeMillis() - started)
        }
        validateAndStore(normalized.json, options, normalized.warnings, started)
    }

    suspend fun importUrl(url: String, options: ImportOptions = ImportOptions(origin = ImportOrigin.URL)): ImportReport =
        withContext(AppDispatchers.Io) {
            val started = System.currentTimeMillis()
            if (url.isBlank()) {
                return@withContext ImportReport.failure(options.origin, SourceIssueCodes.URL, "地址不能为空")
            }
            val normalized = ImportNormalizer.fromUrl(
                url,
                ParseOptions(maxBytes = options.maxBytes, allowLan = options.allowLan),
                options.filename,
            )
            if (normalized.json == null) {
                val issue = normalized.issue!!
                return@withContext ImportReport.failure(options.origin, issue.code, issue.message, System.currentTimeMillis() - started)
            }
            validateAndStore(normalized.json, options, normalized.warnings, started)
        }

    private suspend fun validateAndStore(
        json: String,
        options: ImportOptions,
        preWarnings: List<SourceWarning>,
        startedAt: Long,
    ): ImportReport {
        val validation = SourceSpecParser.parseText(
            json,
            ParseOptions(maxBytes = options.maxBytes, allowLan = options.allowLan),
        )
        if (validation.documentError != null) {
            val e = validation.documentError
            return ImportReport(
                origin = options.origin,
                rejected = validation.rejected,
                warnings = (preWarnings + validation.warnings).distinctBy { it.code + it.message },
                documentError = e,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }
        if (validation.accepted.isEmpty()) {
            val rejected = validation.rejected.ifEmpty {
                listOf(RejectedSource(0, SourceIssueCodes.FIELD, "没有可导入的源"))
            }
            return ImportReport(
                origin = options.origin,
                rejected = rejected,
                warnings = (preWarnings + validation.warnings).distinctBy { it.code + it.message },
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }

        // 1) 先落库（幂等），保证“离线导入也成功”
        val outcome = repository.addSources(validation.accepted, userTouched = options.origin != ImportOrigin.BUILTIN)
        val warnings = (preWarnings + validation.warnings).toMutableList()
        for ((newId, reusedId) in outcome.reused) {
            warnings.add(
                SourceWarning(
                    SourceIssueCodes.W_DUPLICATE_ENDPOINT,
                    "端点已存在，已复用源 id：$newId → $reusedId",
                    reusedId,
                ),
            )
        }

        // 2) L3 连通 + L4 内容探测（失败只告警，不删源）
        val probes = if (options.probe) probeAll(validation.accepted) else emptyMap()
        for (outcomeProbe in probes.values) {
            repository.recordProbe(outcomeProbe.sourceId, outcomeProbe)
            if (!outcomeProbe.ok) {
                warnings.add(
                    SourceWarning(
                        SourceIssueCodes.W_UNVERIFIED,
                        "源 ${outcomeProbe.sourceId} 未验证：${outcomeProbe.message ?: outcomeProbe.state.name}",
                        outcomeProbe.sourceId,
                    ),
                )
            }
        }

        val report = ImportReport(
            origin = options.origin,
            accepted = validation.accepted,
            rejected = validation.rejected,
            warnings = warnings.distinctBy { it.code + it.message },
            probes = probes,
            dedupeReused = outcome.reused,
            durationMs = System.currentTimeMillis() - startedAt,
            persisted = outcome.inserted + outcome.updated,
        )
        TvLog.i(tag, report.logLine())
        return report
    }

    /** 探测并发 3、单源最坏 4s(L3)+5.5s(L4)；网络失败不影响导入结果。 */
    private suspend fun probeAll(sources: List<ParsedSource>): Map<String, ProbeOutcome> = coroutineScope {
        val semaphore = Semaphore(3)
        val jobs = sources.map { src ->
            if (src.kind == com.xingmu.tv.data.SourceKind.BUNDLE) return@map async {
                ProbeOutcome(src.id, ProbeState.UNVERIFIED, message = "bundle 不单独探测")
            }
            async {
                semaphore.withPermit {
                    val l3 = SourceProbe.probeL3(src)
                    if (!l3.ok) l3 else SourceProbe.probeL4(src)
                }
            }
        }
        jobs.awaitAll().associateBy { it.sourceId }
    }

    /** 只做校验不落库（自检/预检用；H5 的“先检查再导入”可复用）。 */
    suspend fun validateOnly(text: String, options: ImportOptions): ValidationResult = withContext(AppDispatchers.Io) {
        val normalized = ImportNormalizer.fromText(text, options.filename)
        if (normalized.json == null) {
            ValidationResult(
                emptyList(),
                emptyList(),
                normalized.warnings,
                documentError = RejectedSource(0, normalized.issue!!.code, normalized.issue.message),
            )
        } else {
            SourceSpecParser.parseText(
                normalized.json,
                ParseOptions(maxBytes = options.maxBytes, allowLan = options.allowLan),
            )
        }
    }
}
