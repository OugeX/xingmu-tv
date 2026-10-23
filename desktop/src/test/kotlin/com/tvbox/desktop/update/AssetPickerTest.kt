package com.tvbox.desktop.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AssetPickerTest {

    private fun asset(name: String, url: String = "https://github.com/x/$name") =
        ReleaseAsset(name = name, sizeBytes = 100, downloadUrl = url)

    @Test
    fun `all apk assets means no desktop package`() {
        val assets = listOf(
            asset("xingmu-tv-v0.3.4-e6f5d3dd.apk"),
            asset("xingmu-tv-v0.3.4-r8-170c539d.apk"),
            asset("detail-seed-v1.zip"),
        )
        assertNull(AssetPicker.best(assets, windows = true))
    }

    @Test
    fun `highest asset version wins not release tag`() {
        val assets = listOf(
            asset("XingmuTV-0.1.0.msi"),
            asset("XingmuTV-0.2.0.exe"),
        )
        val picked = AssetPicker.best(assets, windows = true)
        assertNotNull(picked)
        assertEquals("0.2.0", picked.version)
        assertEquals(AssetKind.EXE, picked.kind, "Windows 同时只有 0.2.0 的 exe，应回退 exe")
    }

    @Test
    fun `windows prefers msi at same version`() {
        val assets = listOf(
            asset("XingmuTV-0.2.0.exe"),
            asset("XingmuTV-0.2.0.msi"),
        )
        val picked = AssetPicker.best(assets, windows = true)
        assertNotNull(picked)
        assertEquals(AssetKind.MSI, picked.kind)
    }

    @Test
    fun `non windows prefers exe at same version`() {
        val assets = listOf(
            asset("XingmuTV-0.2.0.msi"),
            asset("XingmuTV-0.2.0.exe"),
        )
        val picked = AssetPicker.best(assets, windows = false)
        assertNotNull(picked)
        assertEquals(AssetKind.EXE, picked.kind)
    }

    @Test
    fun `sha256 sidecar is ignored`() {
        val assets = listOf(
            asset("XingmuTV-0.2.0.exe.sha256"),
            asset("XingmuTV-0.2.0.msi.sha256"),
        )
        assertNull(AssetPicker.best(assets, windows = true))
    }

    @Test
    fun `version segment is bounded`() {
        assertNotNull(AssetPicker.parse(asset("XingmuTV-0.2.0.msi")))
        assertNull(AssetPicker.parse(asset("XingmuTV-0.2.0.msi.sha256")))
        assertNull(AssetPicker.parse(asset("XingmuTV-0.2.0.apk")))
        assertNull(AssetPicker.parse(asset("XingmuTV-0.2.0.zip")))
        assertNull(AssetPicker.parse(asset("XingmuTV-0.2.0.msi", url = "ftp://example.com/a.msi")))
    }

    @Test
    fun `windows falls back to exe when msi missing`() {
        val picked = AssetPicker.best(listOf(asset("XingmuTV-0.2.0.exe")), windows = true)
        assertEquals(AssetKind.EXE, picked?.kind)
    }
}
