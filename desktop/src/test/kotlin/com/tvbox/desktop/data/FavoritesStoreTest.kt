package com.tvbox.desktop.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class FavoritesStoreTest {

    private val dir = Files.createTempDirectory("t35-favorites")
    private var now = 1_000L

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun store() = FavoritesStore(dir.resolve("favorites.json")) { now }

    @Test
    fun `vod favorite add is deduped`() {
        val store = store()
        store.addVod("bfzy1", "100", "流浪地球", "https://img/a.jpg")
        now = 2_000L
        store.addVod("bfzy1", "100", "流浪地球", "https://img/a.jpg")
        assertEquals(1, store.vodList().size)
        assertTrue(store.isVodFavorite("bfzy1", "100"))
        assertEquals(2_000L, store.vodList().first().updatedAt)
    }

    @Test
    fun `vod toggle adds then removes`() {
        val store = store()
        assertTrue(store.toggleVod("bfzy1", "100", "流浪地球", ""))
        assertTrue(store.isVodFavorite("bfzy1", "100"))
        assertFalse(store.toggleVod("bfzy1", "100", "流浪地球", ""))
        assertFalse(store.isVodFavorite("bfzy1", "100"))
        assertTrue(store.vodList().isEmpty())
    }

    @Test
    fun `live favorite add is deduped and removable`() {
        val store = store()
        store.addLive("cctv1", "CCTV-1 综合", "央视频道")
        store.addLive("cctv1", "CCTV-1 综合", "央视频道")
        assertEquals(1, store.liveList().size)
        assertTrue(store.isLiveFavorite("cctv1"))
        store.removeLive("cctv1")
        assertFalse(store.isLiveFavorite("cctv1"))
    }

    @Test
    fun `vod and live favorites are independent and persist`() {
        val store = store()
        store.addVod("bfzy1", "100", "测试剧", "")
        store.addLive("cctv1", "CCTV-1 综合", "央视频道")

        val reloaded = store()
        assertEquals(1, reloaded.vodList().size)
        assertEquals(1, reloaded.liveList().size)
        assertEquals("测试剧", reloaded.vodList().first().name)
        assertEquals("cctv1", reloaded.liveList().first().channelId)
    }
}
