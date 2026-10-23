package com.tvbox.desktop.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files

class WatchProgressStoreTest {

    private val dir = Files.createTempDirectory("t35-progress")
    private var now = 1_000L

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun store() = WatchProgressStore(dir.resolve("progress.json")) { now }

    private fun progress(
        sourceId: String = "bfzy1",
        vodId: String = "100",
        name: String = "测试剧",
        pic: String = "https://img/a.jpg",
        playFrom: String = "bfzym3u8",
        episodeIndex: Int = 1,
        episodeTitle: String = "第1集",
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long = 0L,
    ) = WatchProgress(
        vodId = vodId,
        sourceId = sourceId,
        name = name,
        pic = pic,
        playFrom = playFrom,
        episodeIndex = episodeIndex,
        episodeTitle = episodeTitle,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
    )

    @Test
    fun `threshold requires 60s or 5 percent`() {
        assertFalse(WatchProgressStore.shouldRecord(30_000, 1_000_000))
        assertFalse(WatchProgressStore.shouldRecord(59_000, 2_000_000))
        assertFalse(WatchProgressStore.shouldRecord(49_000, 1_000_000))
        assertTrue(WatchProgressStore.shouldRecord(50_000, 1_000_000))
        assertTrue(WatchProgressStore.shouldRecord(59_000, 1_000_000))
        assertTrue(WatchProgressStore.shouldRecord(60_000, 1_000_000))
        assertTrue(WatchProgressStore.shouldRecord(120_000, 0))
        assertFalse(WatchProgressStore.shouldRecord(0, 0))
    }

    @Test
    fun `short position below 5 percent is not stored`() {
        val outcome = store().save(progress(positionMs = 30_000, durationMs = 1_000_000))
        assertTrue(outcome.skipped)
        assertNull(outcome.stored)
        assertNull(store().lastFor("bfzy1", "100"))
        assertTrue(store().list().isEmpty())
    }

    @Test
    fun `position above threshold is stored and persists`() {
        now = 2_000L
        val outcome = store().save(progress(positionMs = 120_000, durationMs = 1_000_000))
        assertFalse(outcome.skipped)
        assertEquals(120_000L, outcome.stored?.positionMs)
        assertEquals(2_000L, outcome.stored?.updatedAt)

        val reloaded = store().lastFor("bfzy1", "100")
        assertEquals(120_000L, reloaded?.positionMs)
        assertEquals("测试剧", reloaded?.name)
        assertEquals("bfzym3u8", reloaded?.playFrom)
    }

    @Test
    fun `five percent is enough even if under 60s`() {
        val outcome = store().save(progress(positionMs = 50_000, durationMs = 1_000_000))
        assertFalse(outcome.skipped)
        assertEquals(50_000L, store().lastFor("bfzy1", "100")?.positionMs)
    }

    @Test
    fun `95 percent marks watched and advances episode`() {
        val outcome = store().save(
            progress(positionMs = 960_000, durationMs = 1_000_000, episodeIndex = 3, episodeTitle = "第3集"),
            nextEpisodeTitle = "第4集",
        )
        assertTrue(outcome.markedWatched)
        assertTrue(outcome.advancedEpisode)
        assertEquals(4, outcome.stored?.episodeIndex)
        assertEquals(0L, outcome.stored?.positionMs)
        assertEquals("第4集", store().lastFor("bfzy1", "100")?.episodeTitle)
        assertEquals(setOf(3), store().watchedEpisodes("bfzy1", "100"))
    }

    @Test
    fun `latest save replaces previous record for same vod`() {
        now = 1_000L
        store().save(progress(positionMs = 100_000, durationMs = 1_000_000))
        now = 5_000L
        store().save(progress(positionMs = 300_000, durationMs = 1_000_000))
        val list = store().list()
        assertEquals(1, list.size, "同一 sourceId+vodId 只保留最近一条")
        assertEquals(300_000L, list.first().positionMs)
        assertEquals(5_000L, list.first().updatedAt)
        assertEquals(300_000L, store().lastFor("bfzy1", "100")?.positionMs)
    }

    @Test
    fun `list sorts by updatedAt desc and caps continue list`() {
        now = 100L
        store().save(progress(vodId = "a", positionMs = 70_000, durationMs = 1_000_000))
        now = 300L
        store().save(progress(vodId = "b", positionMs = 80_000, durationMs = 1_000_000))
        now = 200L
        store().save(progress(vodId = "c", positionMs = 90_000, durationMs = 1_000_000))

        val ordered = store().list().map { it.vodId }
        assertEquals(listOf("b", "c", "a"), ordered)
        assertEquals(3, store().listRecent(8).size)
        assertEquals(listOf("b", "c"), store().listRecent(2).map { it.vodId })
    }

    @Test
    fun `watched episodes persist across store instances`() {
        store().markWatched("bfzy1", "100", 1)
        store().markWatched("bfzy1", "100", 2)
        assertEquals(setOf(1, 2), store().watchedEpisodes("bfzy1", "100"))
        assertEquals(emptySet(), store().watchedEpisodes("bfzy1", "other"))
    }

    @Test
    fun `remove deletes only the target record`() {
        store().save(progress(vodId = "a", positionMs = 70_000, durationMs = 1_000_000))
        store().save(progress(vodId = "b", positionMs = 80_000, durationMs = 1_000_000))
        store().remove("bfzy1", "a")
        assertEquals(listOf("b"), store().list().map { it.vodId })
        assertNull(store().lastFor("bfzy1", "a"))
    }

    @Test
    fun `explicit updatedAt is respected`() {
        store().save(progress(positionMs = 70_000, durationMs = 1_000_000, updatedAt = 42L))
        assertEquals(42L, store().lastFor("bfzy1", "100")?.updatedAt)
    }
}
