package com.xingmu.tv.data

import com.xingmu.tv.core.Result
import com.xingmu.tv.core.TvLog

/**
 * 详情本地优先（t27）：命中预置表直接返回，并给出「是否过期（7 天）」让调用方后台静默刷新。
 *
 * 日志格式固定为 `DetailPerf source=seed in=<n>ms ...`，便于 logcat/验收脚本检索。
 */
class DetailSeedDetails(
    private val store: DetailSeedStore,
    private val enabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = { TvLog.i("DetailPerf", it) },
) {

    data class Hit(
        val detail: VodDetail,
        val stale: Boolean,
        val elapsedMs: Long,
    )

    /** 未开启预置数据 / 未命中 / 解析失败都返回 null，调用方继续走网络。 */
    fun lookup(vodId: String, sourceId: String): Hit? {
        if (!enabled() || vodId.isBlank() || sourceId.isBlank()) return null
        val started = clock()
        val seedHit = store.find(vodId, sourceId) ?: return null
        val detail = parseDetail(seedHit.entry.detailJson, sourceId, vodId) ?: run {
            logger("source=seed parse-failed vod=$vodId")
            return null
        }
        val elapsedMs = clock() - started
        val stale = seedHit.ageMs >= STALE_MS
        logger(
            "source=seed in=${elapsedMs}ms vod=$vodId age=${seedHit.ageMs / HOUR_MS}h stale=$stale",
        )
        return Hit(detail = detail, stale = stale, elapsedMs = elapsedMs)
    }

    /** 网络刷新成功后覆盖本地行（只覆盖包里已有的作品，避免表无限膨胀）。 */
    fun refreshFromNetwork(vodId: String, sourceId: String, name: String, year: String, rawDetailJson: String): Boolean {
        if (!enabled()) return false
        if (vodId.isBlank() || sourceId.isBlank() || rawDetailJson.isBlank()) return false
        if (store.find(vodId, sourceId) == null) return false
        store.upsert(
            SeedEntry(
                vodId = vodId,
                sourceId = sourceId,
                name = name,
                year = year,
                fetchedAtMs = clock(),
                detailJson = rawDetailJson,
            ),
        )
        return true
    }

    /** 预置包里的单条 MacCMS item 需要包一层 {code,list} 才能复用在线解析器。 */
    private fun parseDetail(rawItemJson: String, sourceId: String, vodId: String): VodDetail? {
        val wrapped = """{"code":1,"list":[$rawItemJson]}"""
        return when (val parsed = MacCmsParser.parseDetail(wrapped, sourceId, vodId)) {
            is Result.Ok -> parsed.value
            is Result.Err -> null
        }
    }

    companion object {
        /** 7 天过期：过期后仍先返回本地（秒开），同时后台静默刷新。 */
        const val STALE_MS = 7L * 24 * 60 * 60 * 1000
        private const val HOUR_MS = 60 * 60 * 1000L
    }
}
