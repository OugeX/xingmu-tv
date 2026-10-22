package com.xingmu.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UPDATE_SPEC §2：R8 / 标准包选择。 */
class UpdateAssetSelectorTest {

    private fun asset(name: String, size: Long = 1_000L) =
        ReleaseAsset(name = name, sizeBytes = size, downloadUrl = "https://example.com/$name")

    private val assets = listOf(
        asset("app-debug.apk", 12_000_000L),
        asset("xingmu-tv-v0.3.4-abc12345.apk", 12_900_000L),
        asset("xingmu-tv-v0.3.4-r8-def67890.apk", 3_000_000L),
        asset("detail-seed-v1.zip", 387_462L),
        asset("xingmu-tv-v0.3.40-old.apk", 9_000_000L),
    )

    @Test
    fun `low storage picks r8`() {
        val pick = UpdateAssetSelector.select(assets, "0.3.4", freeBytes = 120L * 1024 * 1024)
        assertEquals(ApkFlavor.R8, pick?.flavor)
        assertEquals("xingmu-tv-v0.3.4-r8-def67890.apk", pick?.asset?.name)
    }

    @Test
    fun `plenty storage picks standard`() {
        val pick = UpdateAssetSelector.select(assets, "0.3.4", freeBytes = 2L * 1024 * 1024 * 1024)
        assertEquals(ApkFlavor.STANDARD, pick?.flavor)
        assertEquals("xingmu-tv-v0.3.4-abc12345.apk", pick?.asset?.name)
    }

    @Test
    fun `no asset for version returns null`() {
        assertNull(UpdateAssetSelector.select(assets, "0.4.0", freeBytes = Long.MAX_VALUE))
        assertNull(UpdateAssetSelector.select(emptyList(), "0.3.4", freeBytes = 0L))
    }

    @Test
    fun `falls back to other flavor when preferred is missing`() {
        val onlyStandard = assets.filter { !it.name.contains("-r8-") }
        val lowStorage = UpdateAssetSelector.select(onlyStandard, "0.3.4", freeBytes = 10L * 1024 * 1024)
        assertEquals(ApkFlavor.STANDARD, lowStorage?.flavor)

        val onlyR8 = assets.filter { it.name.contains("-r8-") }
        val highStorage = UpdateAssetSelector.select(onlyR8, "0.3.4", freeBytes = 10L * 1024 * 1024 * 1024)
        assertEquals(ApkFlavor.R8, highStorage?.flavor)
    }

    @Test
    fun `ignores non apk and non matching version`() {
        val pick = UpdateAssetSelector.select(assets, "0.3.4", freeBytes = Long.MAX_VALUE)
        assertTrue(pick?.asset?.name?.endsWith(".apk") == true)
        // v0.3.40 不能被当成 0.3.4（数字边界）
        assertEquals("xingmu-tv-v0.3.4-abc12345.apk", pick?.asset?.name)
    }
}
