package com.tvbox.desktop.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files

class RecentStoreTest {

    private val dir = Files.createTempDirectory("t35-recent")
    private var now = 1_000L

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun store() = RecentStore(dir.resolve("recent.json"), clock = { now })

    @Test
    fun `recent records are newest first`() {
        val store = store()
        now = 100L
        store.record("cctv1", "CCTV-1", "央视频道")
        now = 200L
        store.record("cctv2", "CCTV-2", "央视频道")

        assertEquals(listOf("cctv2", "cctv1"), store.list().map { it.channelId })
        assertEquals(200L, store.list().first().updatedAt)
    }

    @Test
    fun `duplicate channel is moved to top without growing`() {
        val store = store()
        now = 100L
        store.record("cctv1", "CCTV-1", "央视频道")
        now = 200L
        store.record("cctv2", "CCTV-2", "央视频道")
        now = 300L
        store.record("cctv1", "CCTV-1", "央视频道")

        val list = store.list()
        assertEquals(2, list.size)
        assertEquals("cctv1", list.first().channelId)
        assertEquals(300L, list.first().updatedAt)
    }

    @Test
    fun `recent is capped at 20`() {
        val store = store()
        repeat(25) { index ->
            now = (index + 1).toLong()
            store.record("ch$index", "频道$index", "地方频道")
        }
        val list = store.list()
        assertEquals(RecentStore.MAX_ITEMS, list.size)
        assertEquals("ch24", list.first().channelId)
        assertTrue(list.none { it.channelId == "ch0" })
    }

    @Test
    fun `recent persists and remove works`() {
        store().record("cctv1", "CCTV-1", "央视频道")
        now = 2_000L
        store().record("cctv2", "CCTV-2", "央视频道")
        store().remove("cctv1")

        val reloaded = store().list()
        assertEquals(1, reloaded.size)
        assertEquals("cctv2", reloaded.first().channelId)
    }
}
