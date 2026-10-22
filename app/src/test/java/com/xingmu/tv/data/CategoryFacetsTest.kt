package com.xingmu.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t34：分类页纯逻辑（数量角标解析 / 能力探测判定 / 空结果放宽顺序 / 三条榜单）。
 */
class CategoryFacetsTest {

    // ── 1) 数量角标解析 ────────────────────────────────────────────────
    @Test
    fun parsesTotalFromListResponse() {
        val json = """{"code":1,"msg":"数据列表","page":1,"pagecount":373,"limit":20,"total":7442,"list":[]}"""
        assertEquals(7442, CategoryFacets.parseTotal(json))
    }

    @Test
    fun returnsNullWhenTotalMissingOrError() {
        assertNull("没有 total 字段", CategoryFacets.parseTotal("""{"code":1,"list":[]}"""))
        assertNull("code!=1", CategoryFacets.parseTotal("""{"code":0,"total":10}"""))
        assertNull("非法 JSON", CategoryFacets.parseTotal("not json"))
    }

    @Test
    fun facetCacheFreshness() {
        val now = 1_800_000_000_000L
        assertTrue(CategoryFacets.isFresh(now - 1000, now))
        assertFalse("超过 24h 必须重新取", CategoryFacets.isFresh(now - CategoryFacets.COUNT_TTL_MS - 1, now))
        assertFalse("未写过（0）视为不新鲜", CategoryFacets.isFresh(0, now))
    }

    // ── 2) 能力探测判定 ────────────────────────────────────────────────
    @Test
    fun totalChangedMeansSupported() {
        // t=22 → 7442；带 area=大陆 后 total 变了 → 服务端采纳 area
        assertEquals(FilterSupport.SUPPORTED, CapabilityDecider.decideOne(7442, 1200))
    }

    @Test
    fun totalUnchangedMeansUnsupported() {
        // bfzy1 实测：area / by 都不改变 total → 不显示该筛选
        assertEquals(FilterSupport.UNSUPPORTED, CapabilityDecider.decideOne(7442, 7442))
        val (area, sort) = CapabilityDecider.decide(7442, 7442, 7442)
        assertEquals(FilterSupport.UNSUPPORTED, area)
        assertEquals(FilterSupport.UNSUPPORTED, sort)
        assertFalse(FilterCapability(area, sort).showArea)
        assertFalse(FilterCapability(area, sort).showSort)
    }

    @Test
    fun unknownWhenBaselineMissing() {
        assertEquals(FilterSupport.UNKNOWN, CapabilityDecider.decideOne(0, 100))
        assertEquals(FilterSupport.UNKNOWN, CapabilityDecider.decideOne(-1, 100))
        assertEquals(FilterSupport.UNKNOWN, CapabilityDecider.decideOne(7442, null))
    }

    @Test
    fun majorityVoteAcrossSources() {
        assertEquals(FilterSupport.SUPPORTED, CapabilityDecider.majority(3, 0))
        assertEquals("2 支持 1 反对 → 显示但标注部分源不支持", FilterSupport.PARTIAL, CapabilityDecider.majority(2, 1))
        assertEquals(FilterSupport.UNSUPPORTED, CapabilityDecider.majority(1, 2))
        assertEquals(FilterSupport.UNKNOWN, CapabilityDecider.majority(0, 0))
        assertTrue(FilterCapability(FilterSupport.PARTIAL, FilterSupport.SUPPORTED).partialNote)
    }

    // ── 3) 空结果放宽顺序 ──────────────────────────────────────────────
    @Test
    fun noRelaxWhenResultsExist() {
        assertEquals(emptyList<CategoryFallback.Step>(), CategoryFallback.plan(total = 165, hasYearFilter = true))
        assertFalse(CategoryFallback.needsRelaxYear(165, true))
    }

    @Test
    fun relaxYearThenShowEmpty() {
        assertEquals(
            listOf(CategoryFallback.Step.RELAX_YEAR, CategoryFallback.Step.SHOW_EMPTY),
            CategoryFallback.plan(total = 0, hasYearFilter = true),
        )
        assertTrue(CategoryFallback.needsRelaxYear(0, true))
    }

    @Test
    fun showEmptyDirectlyWithoutYear() {
        assertEquals(
            listOf(CategoryFallback.Step.SHOW_EMPTY),
            CategoryFallback.plan(total = 0, hasYearFilter = false),
        )
        assertFalse(CategoryFallback.needsRelaxYear(0, false))
    }

    // ── 4) 三条榜单 ────────────────────────────────────────────────────
    private fun vod(id: String, score: String = "0.0", remarks: String = "", updatedAtMs: Long = 0L) = VodSummary(
        vodId = id,
        sourceId = "bfzy1",
        name = id,
        pic = "",
        remarks = remarks,
        year = "2026",
        typeId = "22",
        typeName = "喜剧片",
        score = score,
        updatedAtMs = updatedAtMs,
    )

    @Test
    fun mostUpdatedSortsByUpdateTime() {
        val list = listOf(vod("a", updatedAtMs = 100), vod("b", updatedAtMs = 300), vod("c", updatedAtMs = 200))
        assertEquals(listOf("b", "c", "a"), CategoryBoards.mostUpdated(list).map { it.vodId })
        assertEquals(2, CategoryBoards.mostUpdated(list, limit = 2).size)
    }

    @Test
    fun topRatedFiltersFakeScores() {
        val list = listOf(
            vod("fake10", score = "10.0"),
            vod("fake1", score = "1.0"),
            vod("real7", score = "7.5"),
            vod("real9", score = "9.0"),
        )
        // 10.0 也是 bfzy1 的整齐假分（captain 实测），排行榜不采用；<=1.0 同样剔除
        assertEquals(listOf("real9", "real7"), CategoryBoards.topRated(list).map { it.vodId })
        assertFalse(CategoryBoards.isTrustedScore("10.0"))
        assertTrue(CategoryBoards.isTrustedScore("8.4"))
    }

    @Test
    fun recentlyCompletedOnlyCompletedRemarks() {
        val list = listOf(
            vod("done", remarks = "全40集", updatedAtMs = 10),
            vod("finished", remarks = "已完结", updatedAtMs = 30),
            vod("airing", remarks = "更新至12集", updatedAtMs = 20),
        )
        assertEquals(listOf("finished", "done"), CategoryBoards.recentlyCompleted(list).map { it.vodId })
        assertTrue(CategoryBoards.isCompleted("全26集"))
        assertTrue(CategoryBoards.isCompleted("已完结"))
        assertFalse(CategoryBoards.isCompleted("更新至3集"))
        assertFalse(CategoryBoards.isCompleted(""))
    }
}
