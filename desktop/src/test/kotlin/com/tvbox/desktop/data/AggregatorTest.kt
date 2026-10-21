package com.tvbox.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AggregatorTest {

    private fun summary(source: String, name: String, year: String, score: String = "") = VodSummary(
        vodId = "$source-id",
        sourceId = source,
        sourceName = source.uppercase(),
        name = name,
        pic = "",
        remarks = "",
        year = year,
        typeId = "20",
        typeName = "科幻",
        score = score,
        refs = listOf(VodRef(source, source.uppercase(), "$source-id")),
    )

    @Test
    fun `same name and year merge refs`() {
        val merged = Aggregator(MacCmsClient(Http(), FileCache(java.nio.file.Paths.get("build/tmp/test-cache")))).merge(
            listOf(
                summary("s1", "流浪地球", "2019", "8.0"),
                summary("s2", "流浪地球 ", "2019", "8.6"),
                summary("s3", "流浪地球2", "2023"),
            ),
        )
        assertEquals(2, merged.size)
        val first = merged.first { it.name.contains("流浪地球") && !it.name.contains("2") }
        assertEquals(2, first.sourceCount)
        assertEquals(2, first.refs.size)
        assertEquals(setOf("s1", "s2"), first.refs.map { it.sourceId }.toSet())
        assertEquals("8.6", first.score, "保留评分更高的代表条目")
    }

    @Test
    fun `different year is not merged`() {
        val merged = Aggregator(MacCmsClient(Http(), FileCache(java.nio.file.Paths.get("build/tmp/test-cache")))).merge(
            listOf(summary("s1", "同名电影", "2019"), summary("s2", "同名电影", "2024")),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `sources sorted by source count`() {
        val single = summary("s1", "独播影片", "2020")
        val merged = Aggregator(MacCmsClient(Http(), FileCache(java.nio.file.Paths.get("build/tmp/test-cache")))).merge(
            listOf(single, summary("s2", "热门影片", "2021"), summary("s3", "热门影片", "2021")),
        )
        assertTrue(merged.first().sourceCount >= 2)
    }
}
