package com.tvbox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * t27：SQLite 导入 + 查询路径（真实 SQLite 引擎 + 真实 sample 数据）。
 */
class DetailSeedStoreTest {

    private val sample = SeedPackage.parse(TestFiles.seedSample.readText())

    @Test
    fun importSampleThenQueryHitAndMiss() {
        JdbcSeedDatabase().use { db ->
            val store = DetailSeedStore(db)
            val importMs = store.import(sample.toSeedEntries(), "v1", "bfzy1")

            val stats = store.stats()
            assertEquals(20, stats.count)
            assertEquals("v1", stats.version)
            assertEquals("bfzy1", stats.sourceId)
            assertTrue("导入耗时应可测", importMs >= 0)

            val hit = store.find("169066", "bfzy1")
            assertNotNull("sample 第一条必须能命中", hit)
            assertEquals("丑妻仙缘第七季", hit!!.entry.name)
            assertTrue(hit.entry.detailJson.contains("vod_play_url"))

            assertNull("不同源不命中", store.find("169066", "ffzy"))
            assertNull("不存在的 id 不命中", store.find("404404", "bfzy1"))
        }
    }

    @Test
    fun import500EntriesWithinOneSecond() {
        // 模拟真实包体积：500 条 × 约 5.7KB JSON（release 包 2.75MB / 500）
        val filler = "x".repeat(5_000)
        val entries = (1..500).map { index ->
            SeedEntry(
                vodId = "seed$index",
                sourceId = "bfzy1",
                name = "测试作品 $index",
                year = "2026",
                fetchedAtMs = System.currentTimeMillis(),
                detailJson = """{"vod_id":"seed$index","vod_name":"测试作品 $index","vod_play_url":"第1集${'$'}https://example.com/$index.m3u8","filler":"$filler"}""",
            )
        }
        JdbcSeedDatabase().use { db ->
            val store = DetailSeedStore(db)
            val importMs = store.import(entries, "v1", "bfzy1")
            println("DetailSeed import 500 entries in ${importMs}ms")
            assertEquals(500, store.stats().count)
            assertTrue("500 条导入必须 ≤1000ms，实际 ${importMs}ms", importMs <= 1_000)
            assertNotNull(store.find("seed250", "bfzy1"))
        }
    }

    @Test
    fun upsertOverwritesStoredJson() {
        JdbcSeedDatabase().use { db ->
            val store = DetailSeedStore(db)
            store.import(sample.toSeedEntries(), "v1", "bfzy1")
            val before = store.find("169066", "bfzy1")!!.entry
            store.upsert(
                before.copy(
                    fetchedAtMs = System.currentTimeMillis(),
                    detailJson = before.detailJson.replace("\"vod_id\":\"169066\"", "\"vod_id\":\"169066\",\"refreshed\":true"),
                ),
            )
            val after = store.find("169066", "bfzy1")!!.entry
            assertTrue("刷新后 JSON 必须被覆盖", after.detailJson.contains("\"refreshed\":true"))
            assertEquals(20, store.stats().count)
        }
    }
}
