package com.xingmu.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t32：首页热度排序单测（纯函数，无 Android/网络依赖）。
 *
 * 契约顺序：`sourceCount desc → hits desc → score desc → updatedAtMs desc`；
 * 候选池为空时 hero 必须回退到调用方给的「现有第一条」，不能返回 null/崩溃。
 */
class HomeHeatTest {

    private fun vod(
        id: String,
        sourceCount: Int = 1,
        hits: Long = 0L,
        score: String = "0.0",
        updatedAtMs: Long = 0L,
        name: String = "片子$id",
        year: String = "2026",
    ) = VodSummary(
        vodId = id,
        sourceId = "bfzy1",
        name = name,
        pic = "",
        remarks = "",
        year = year,
        typeId = "22",
        typeName = "喜剧片",
        score = score,
        sourceCount = sourceCount,
        updatedAtMs = updatedAtMs,
        hits = hits,
    )

    @Test
    fun sourceCountIsThePrimarySignal() {
        val single = vod("a", sourceCount = 1, hits = 999_999, score = "10.0", updatedAtMs = 9_000_000_000_000L)
        val triple = vod("b", sourceCount = 3, hits = 0, score = "1.0", updatedAtMs = 1L)
        val ranked = HomeHeat.rank(listOf(single, triple))
        assertEquals("跨源重合度最高必须排第一（hits/score/time 不能翻盘）", "b", ranked.first().vodId)
    }

    @Test
    fun hitsBreaksTieWhenSourceCountEqual() {
        val hot = vod("hot", sourceCount = 2, hits = 12_000)
        val cold = vod("cold", sourceCount = 2, hits = 0)
        val ranked = HomeHeat.rank(listOf(cold, hot))
        assertEquals(listOf("hot", "cold"), ranked.map { it.vodId })
    }

    @Test
    fun scoreBreaksTieBeforeTime() {
        val highScoreOld = vod("highScore", sourceCount = 2, hits = 0, score = "8.0", updatedAtMs = 1L)
        val lowScoreNew = vod("lowScore", sourceCount = 2, hits = 0, score = "4.0", updatedAtMs = 9_999L)
        val ranked = HomeHeat.rank(listOf(lowScoreNew, highScoreOld))
        assertEquals("score 在 time 之前决定顺序", listOf("highScore", "lowScore"), ranked.map { it.vodId })
    }

    @Test
    fun timeIsOnlyTieBreak() {
        val newer = vod("newer", sourceCount = 2, hits = 0, score = "1.0", updatedAtMs = 9_000L)
        val older = vod("older", sourceCount = 2, hits = 0, score = "1.0", updatedAtMs = 1_000L)
        val ranked = HomeHeat.rank(listOf(older, newer))
        assertEquals("完全同分时新片优先", listOf("newer", "older"), ranked.map { it.vodId })
    }

    @Test
    fun fakeScoresDoNotBeatRealScores() {
        // 镜像站 1.0/0.0 是默认假分，按 0 处理；7.3 才是真实评分
        val fakeTen = vod("fakeTen", sourceCount = 2, score = "1.0", updatedAtMs = 9_000L)
        val real = vod("real", sourceCount = 2, score = "7.3", updatedAtMs = 1_000L)
        val ranked = HomeHeat.rank(listOf(fakeTen, real))
        assertEquals(listOf("real", "fakeTen"), ranked.map { it.vodId })
        assertEquals(0.0, HomeHeat.scoreOf("1.0"), 0.0001)
        assertEquals(7.3, HomeHeat.scoreOf("7.3"), 0.0001)
    }

    @Test
    fun emptyPoolFallsBackToFirstItem() {
        assertNull("空池且无兜底 → null（UI 显示骨架/空态）", HomeHeat.heroFrom(emptyList(), null))
        val fallback = vod("fallback")
        assertEquals("fallback", HomeHeat.heroFrom(emptyList(), fallback)?.vodId)
        assertEquals("a", HomeHeat.heroFrom(listOf(vod("a")), fallback)?.vodId)
    }

    @Test
    fun keyNormalizesTitleNoiseAndYear() {
        assertEquals(
            HomeHeat.key("名侦探柯南 第1季", "2026"),
            HomeHeat.key("名侦探柯南（全12集）", "2026"),
        )
        assertTrue(HomeHeat.key("A", "2026") != HomeHeat.key("A", "2025"))
    }

    @Test
    fun mergeKeepsStrongestSignalsAndFillsBlanks() {
        val a = vod("a", sourceCount = 1, hits = 10, score = "1.0", updatedAtMs = 100L)
        val b = vod("a", hits = 99, score = "7.0", updatedAtMs = 500L).copy(pic = "https://x/y.jpg")
        val merged = HomeHeat.merge(a, b)
        assertEquals(99L, merged.hits)
        assertEquals("7.0", merged.score)
        assertEquals(500L, merged.updatedAtMs)
        assertEquals("https://x/y.jpg", merged.pic)
    }

    @Test
    fun rankIsStableForEqualItems() {
        val items = listOf(vod("a"), vod("b"), vod("c"))
        assertEquals(listOf("a", "b", "c"), HomeHeat.rank(items).map { it.vodId })
        assertEquals(items.size, HomeHeat.rank(items).size)
    }
}
