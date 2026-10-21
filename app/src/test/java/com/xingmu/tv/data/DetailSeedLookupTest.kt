package com.xingmu.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * t27：详情本地优先（命中 → `DetailPerf source=seed in=<n>ms` → 过期标记）。
 */
class DetailSeedLookupTest {

    private val sample = SeedPackage.parse(TestFiles.seedSample.readText())

    private fun storeWithSample(db: JdbcSeedDatabase): DetailSeedStore =
        DetailSeedStore(db).also { it.import(sample.toSeedEntries(), "v1", "bfzy1") }

    @Test
    fun seedHitLogsSourceSeedWithLatency() {
        JdbcSeedDatabase().use { db ->
            val store = storeWithSample(db)
            val logs = mutableListOf<String>()
            val details = DetailSeedDetails(store, enabled = { true }, logger = { logs += it })

            val hit = details.lookup("169066", "bfzy1")

            assertNotNull("必须命中预置数据", hit)
            assertEquals("丑妻仙缘第七季", hit!!.detail.name)
            assertTrue("必须带播放线路", hit.detail.episodesByFrom.isNotEmpty())
            assertTrue("本地命中预算：实际 ${hit.elapsedMs}ms", hit.elapsedMs < 300)
            assertEquals(1, logs.size)
            assertTrue(
                "日志格式必须是 DetailPerf source=seed in=<n>ms：${logs.first()}",
                Regex("""source=seed in=\d+ms""").containsMatchIn(logs.first()),
            )
            println("DetailPerf ${logs.first()}")
        }
    }

    @Test
    fun disabledSeedReturnsNull() {
        JdbcSeedDatabase().use { db ->
            val store = storeWithSample(db)
            val details = DetailSeedDetails(store, enabled = { false }, logger = {})
            assertNull(details.lookup("169066", "bfzy1"))
        }
    }

    @Test
    fun sevenDayStalenessFlag() {
        JdbcSeedDatabase().use { db ->
            val store = storeWithSample(db)
            val fresh = DetailSeedDetails(store, enabled = { true }, logger = {})
            assertFalse("刚导入的数据不该过期", fresh.lookup("169066", "bfzy1")!!.stale)

            // 把 fetched_at 改成 8 天前，模拟过期
            db.transaction { session ->
                session.exec(
                    "UPDATE ${DetailSeedSchema.TABLE} SET fetched_at = ? WHERE vod_id = ?",
                    listOf(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000, "169066"),
                )
            }
            val stale = DetailSeedDetails(store, enabled = { true }, logger = {})
            assertTrue("超过 7 天必须标记过期（先返回本地再后台刷新）", stale.lookup("169066", "bfzy1")!!.stale)
        }
    }

    @Test
    fun refreshFromNetworkOnlyTouchesExistingRows() {
        JdbcSeedDatabase().use { db ->
            val store = storeWithSample(db)
            val details = DetailSeedDetails(store, enabled = { true }, logger = {})

            assertTrue(
                details.refreshFromNetwork("169066", "bfzy1", "丑妻仙缘第七季", "2026", """{"vod_id":"169066","vod_name":"刷新"}"""),
            )
            assertTrue(store.find("169066", "bfzy1")!!.entry.detailJson.contains("刷新"))

            assertFalse(
                "包里没有的作品不写库，避免表膨胀",
                details.refreshFromNetwork("999999", "bfzy1", "新作", "2026", """{"vod_id":"999999"}"""),
            )
            assertNull(store.find("999999", "bfzy1"))
        }
    }
}
