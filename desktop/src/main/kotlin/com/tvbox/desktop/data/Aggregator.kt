package com.tvbox.desktop.data

import com.tvbox.desktop.core.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 多源聚合：同一个作品跨源合并成一个条目（refs 记录可用源），
 * 失败源不阻塞其他源，错误以 SourceNote 形式回传给 UI。
 */
class Aggregator(private val macCms: MacCmsClient) {

    data class SourceNote(val sourceId: String, val sourceName: String, val message: String)

    data class Result(
        val items: List<VodSummary>,
        val notes: List<SourceNote>,
        val sourceCount: Int,
        val tookMs: Long,
    )

    suspend fun browse(sources: List<SourceConfig>, category: String, page: Int): Result {
        val targets = sources.filter { it.enabled && it.kind == SourceKind.MACCMS }
        val started = System.currentTimeMillis()
        val notes = mutableListOf<SourceNote>()
        val perSource = coroutineScope {
            targets.map { source ->
                async {
                    val outcome = withTimeoutOrNull(SOURCE_TIMEOUT_MS) { macCms.list(source, category, page) }
                    if (outcome == null) {
                        SourceNote(source.id, source.name, "超时")
                    } else if (!outcome.ok) {
                        SourceNote(source.id, source.name, outcome.error?.message ?: "失败")
                    } else {
                        outcome
                    }
                }
            }.awaitAll()
        }
        perSource.forEach { if (it is SourceNote) notes += it }
        val items = perSource.filterIsInstance<MacCmsClient.Outcome<List<VodSummary>>>()
            .flatMap { it.value.orEmpty() }
        return Result(merge(items), notes, targets.size, System.currentTimeMillis() - started)
    }

    suspend fun search(sources: List<SourceConfig>, keyword: String): Result {
        val targets = sources.filter { it.enabled && it.kind == SourceKind.MACCMS && keyword.isNotBlank() }
        val started = System.currentTimeMillis()
        val notes = mutableListOf<SourceNote>()
        val perSource = coroutineScope {
            targets.map { source ->
                async {
                    val outcome = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { macCms.search(source, keyword, 1) }
                    if (outcome == null) {
                        SourceNote(source.id, source.name, "超时")
                    } else if (!outcome.ok) {
                        SourceNote(source.id, source.name, outcome.error?.message ?: "失败")
                    } else {
                        outcome
                    }
                }
            }.awaitAll()
        }
        perSource.forEach { if (it is SourceNote) notes += it }
        val items = perSource.filterIsInstance<MacCmsClient.Outcome<List<VodSummary>>>()
            .flatMap { it.value.orEmpty() }
        Log.d("Aggregator", "search '$keyword' -> ${items.size} 条 / ${targets.size} 源")
        return Result(merge(items), notes, targets.size, System.currentTimeMillis() - started)
    }

    /** 同名（规范化）+ 年份合并；保留信息最全的一条，refs 取并集。 */
    fun merge(items: List<VodSummary>): List<VodSummary> {
        if (items.size <= 1) return items
        val merged = LinkedHashMap<String, VodSummary>()
        for (item in items) {
            val key = keyOf(item)
            val existing = merged[key]
            if (existing == null) {
                merged[key] = item
            } else {
                val refs = (existing.refs + item.refs)
                    .distinctBy { it.sourceId to it.vodId }
                val primary = if (scoreValue(item.score) > scoreValue(existing.score)) item else existing
                merged[key] = primary.copy(
                    refs = refs,
                    pic = primary.pic.ifBlank { existing.pic.ifBlank { item.pic } },
                    remarks = primary.remarks.ifBlank { existing.remarks },
                    year = primary.year.ifBlank { existing.year },
                    typeName = primary.typeName.ifBlank { existing.typeName },
                )
            }
        }
        return merged.values.sortedWith(
            compareByDescending<VodSummary> { it.sourceCount }
                .thenByDescending { scoreValue(it.score) }
                .thenBy { it.name },
        )
    }

    private fun keyOf(item: VodSummary): String {
        val normalized = item.name
            .lowercase()
            .replace(Regex("[\\s·・:：\\-—–_/\\\\|，,。.!！?？'\"“”‘’()（）\\[\\]【】]+"), "")
        val year = item.year.takeIf { it.matches(Regex("\\d{4}")) }.orEmpty()
        return normalized + "|" + year
    }

    private fun scoreValue(score: String): Double = score.toDoubleOrNull() ?: 0.0

    private companion object {
        const val SOURCE_TIMEOUT_MS = 8_000L
        const val SEARCH_TIMEOUT_MS = 9_000L
    }
}
