package com.tvbox.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveCatalogTest {

    private val snapshot = LiveSeedLoader.parse(TestAssets.read("live_seed.json"), "test")
    private val hdKeys = LiveHd.parseKeys(TestAssets.read("live_hd.json"))

    @Test
    fun `hd file has 114 channels and all match seed`() {
        assertEquals(114, hdKeys.size)
        val catalog = LiveCatalogBuilder.build(snapshot, hdKeys, emptyList(), emptyList())
        assertEquals(114, catalog.category("hd")?.count)
        assertEquals(114, catalog.category("hd")?.channelIds?.distinct()?.size)
        assertTrue(catalog.category("hd")!!.channelIds.all { catalog.channel(it) != null })
    }

    @Test
    fun `category order and counts match android`() {
        val catalog = LiveCatalogBuilder.build(snapshot, hdKeys, emptyList(), emptyList())
        assertEquals(
            listOf("hd", "cctv", "satellite", "local", "favorites", "recent"),
            catalog.categories.map { it.id },
        )
        assertEquals(114, catalog.category("hd")?.count)
        assertEquals(28, catalog.category("cctv")?.count)
        assertEquals(26, catalog.category("satellite")?.count)
        assertEquals(212, catalog.category("local")?.count)
        assertEquals(0, catalog.category("favorites")?.count)
        assertEquals(0, catalog.category("recent")?.count)
        assertEquals("高清专区", catalog.category("hd")?.name)
        assertEquals("收藏", catalog.category("favorites")?.name)
        assertEquals("最近", catalog.category("recent")?.name)
    }

    @Test
    fun `favorites and recent categories preserve stored order after local`() {
        val catalog = LiveCatalogBuilder.build(
            snapshot,
            hdKeys,
            favoriteIds = listOf("cctv1", "cctv2"),
            recentIds = listOf("cctv10", "cctv1"),
        )
        val ids = catalog.categories.map { it.id }
        assertTrue(ids.indexOf("favorites") > ids.indexOf("local"))
        assertTrue(ids.indexOf("recent") > ids.indexOf("favorites"))
        assertEquals(listOf("cctv1", "cctv2"), catalog.category("favorites")?.channelIds)
        assertEquals(2, catalog.category("favorites")?.count)
        assertEquals(listOf("cctv10", "cctv1"), catalog.category("recent")?.channelIds)
        assertEquals(2, catalog.category("recent")?.count)
    }

    @Test
    fun `local group paginates 60 per page into 4 pages`() {
        val local = snapshot.channels.filter { it.groupId == "local" }
        assertEquals(212, local.size)
        assertEquals(4, LivePaging.pageCount(local.size))
        assertEquals(60, LivePaging.page(local, 0).size)
        assertEquals(60, LivePaging.page(local, 1).size)
        assertEquals(60, LivePaging.page(local, 2).size)
        assertEquals(32, LivePaging.page(local, 3).size)
        assertEquals(32, LivePaging.page(local, 99).size, "越界页应回退到最后一页")
    }

    @Test
    fun `invalid category yields empty channels`() {
        val catalog = LiveCatalogBuilder.build(snapshot, hdKeys, emptyList(), emptyList())
        assertTrue(catalog.channelsFor("missing").isEmpty())
    }
}
