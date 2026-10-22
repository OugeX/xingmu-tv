package com.xingmu.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t31：`list(typeId, year)` 的 query 拼装（服务端 t= / year= 两路）。
 */
class MacCmsQueryTest {

    @Test
    fun includesYearWhenProvided() {
        val query = MacCmsQuery.list(typeId = 22, page = 1, order = "time", year = "2026")
        assertTrue("必须带子分类 t=22：$query", query.contains("t=22"))
        assertTrue("必须带 year=2026：$query", query.contains("year=2026"))
        assertTrue(query.startsWith("?ac=detail&"))
        assertEquals("?ac=detail&t=22&pg=1&by=time&year=2026", query)
    }

    @Test
    fun omitsYearWhenBlankOrNull() {
        val withoutYear = MacCmsQuery.list(typeId = 22, page = 2, order = "time", year = null)
        assertFalse("year 为空时不能出现 year=：$withoutYear", withoutYear.contains("year="))
        assertEquals("?ac=detail&t=22&pg=2&by=time", withoutYear)

        val blankYear = MacCmsQuery.list(typeId = 22, page = 1, order = "hits", year = "   ")
        assertFalse(blankYear.contains("year="))
        assertEquals("?ac=detail&t=22&pg=1&by=hits", blankYear)
    }

    @Test
    fun alwaysUsesDetailAcAndNeverArea() {
        val query = MacCmsQuery.list(typeId = 20, page = 1, order = "time", year = "2025")
        assertTrue("列表必须走 ac=detail（ac=list 会忽略 t=）", query.contains("ac=detail"))
        assertFalse("area 服务端忽略，不得拼进 query", query.contains("area="))
    }

    @Test
    fun omitsTypeIdOnlyWhenNull() {
        val noType = MacCmsQuery.list(typeId = null, page = 1, order = "time", year = null)
        assertFalse(noType.contains("t="))
        val zeroType = MacCmsQuery.list(typeId = 0, page = 1, order = "time", year = null)
        assertFalse("typeId=0 视为不限", zeroType.contains("t="))
    }

    @Test
    fun pageIsCoercedToAtLeastOne() {
        assertEquals("?ac=detail&t=22&pg=1&by=time", MacCmsQuery.list(22, 0, "time", null))
        assertEquals("?ac=detail&t=22&pg=1&by=time", MacCmsQuery.list(22, -5, "time", null))
    }

    @Test
    fun cacheKeySeparatesYears() {
        val k2026 = MacCmsQuery.listCacheKey("bfzy1", 22, 1, "time", "2026")
        val k2025 = MacCmsQuery.listCacheKey("bfzy1", 22, 1, "time", "2025")
        val kNone = MacCmsQuery.listCacheKey("bfzy1", 22, 1, "time", null)
        assertTrue("切年份不能命中同一缓存键", k2026 != k2025)
        assertTrue(k2026 != kNone)
        assertTrue(kNone.endsWith("-"))
        assertTrue(k2026.contains("|2026"))
    }
}
