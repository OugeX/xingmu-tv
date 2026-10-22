package com.xingmu.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t31：热度排序信号（captain 实测校正）。
 *
 * bfzy1：`vod_hits` 全 0、`vod_score` 是镜像站假分（10.0/6.0/4.0/1.0），
 * 所以排序以 sourceCount（跨源重合度）为主，更新时间兜底，评分只做 tie-break 且 <1.0 归零。
 */
class VodRankingTest {

    private fun vod(
        id: String,
        sourceCount: Int = 1,
        updatedAtMs: Long = 0L,
        score: String = "0.0",
        name: String = "片子$id",
    ) = VodSummary(
        vodId = id,
        sourceId = "bfzy1",
        name = name,
        pic = "",
        remarks = "",
        year = "2026",
        typeId = "22",
        typeName = "喜剧片",
        score = score,
        sourceCount = sourceCount,
        updatedAtMs = updatedAtMs,
    )

    @Test
    fun sourceCountDominates() {
        val single = vod("a", sourceCount = 1, updatedAtMs = 9_000_000_000_000L, score = "10.0")
        val triple = vod("b", sourceCount = 3, updatedAtMs = 1L, score = "1.0")
        val ranked = VodRanking.rankHot(listOf(single, triple))
        assertEquals("跨源数多的必须排前面（更新时间/评分不能翻盘）", "b", ranked.first().vodId)
    }

    @Test
    fun hitsAllZeroDoesNotAffectOrder() {
        // 没有 hits 字段可读（全 0）→ 同源数同时间同分时必须保持输入顺序（稳定排序）
        val list = listOf(vod("a"), vod("b"), vod("c"))
        val ranked = VodRanking.rankHot(list)
        assertEquals(listOf("a", "b", "c"), ranked.map { it.vodId })
    }

    @Test
    fun allScoresAreFakeOnePointZero() {
        // 所有作品 score 都是 1.0（默认假分）→ 评分归零，排序只看时间
        val older = vod("old", updatedAtMs = 1_000L, score = "1.0")
        val newer = vod("new", updatedAtMs = 9_000L, score = "1.0")
        val ranked = VodRanking.rankHot(listOf(older, newer))
        assertEquals("同源数时新片优先", "new", ranked.first().vodId)
        assertEquals(0.0, VodRanking.effectiveScore("1.0"), 0.0001)
        assertEquals(0.0, VodRanking.effectiveScore("0.9"), 0.0001)
        assertEquals(0.0, VodRanking.effectiveScore("0.0"), 0.0001)
        assertEquals(7.3, VodRanking.effectiveScore("7.3"), 0.0001)
    }

    @Test
    fun singleSourcePoolFallsBackToTimeThenScore() {
        // 候选池全是单源作品（sourceCount=1）→ 时间 desc，时间相同再比评分
        val a = vod("a", sourceCount = 1, updatedAtMs = 5_000L, score = "4.0")
        val b = vod("b", sourceCount = 1, updatedAtMs = 5_000L, score = "8.0")
        val c = vod("c", sourceCount = 1, updatedAtMs = 9_000L, score = "1.0")
        val ranked = VodRanking.rankHot(listOf(a, b, c))
        assertEquals(listOf("c", "b", "a"), ranked.map { it.vodId })
    }

    @Test
    fun rankByScoreIgnoresFakeScores() {
        val fake = vod("fake", score = "1.0", sourceCount = 5)
        val real = vod("real", score = "6.5", sourceCount = 1)
        val zero = vod("zero", score = "0.0")
        val ranked = VodRanking.rankByScore(listOf(fake, zero, real))
        assertEquals(listOf("real", "fake", "zero"), ranked.map { it.vodId })
        assertTrue("假分作品在评分排序里必须垫底于真实分", ranked.first().vodId == "real")
    }

    @Test
    fun doesNotDropItems() {
        val list = (1..50).map { vod("v$it", sourceCount = it % 4, updatedAtMs = it.toLong()) }
        val ranked = VodRanking.rankHot(list)
        assertEquals(list.size, ranked.size)
        assertEquals(list.map { it.vodId }.toSet(), ranked.map { it.vodId }.toSet())
    }
}
