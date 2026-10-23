package com.tvbox.desktop.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReleaseParserTest {

    @Test
    fun `parses tag page and desktop assets`() {
        val json = """
            {
              "tag_name": "v0.3.4",
              "name": "星幕 TV v0.3.4",
              "html_url": "https://github.com/OugeX/xingmu-tv/releases/tag/v0.3.4",
              "prerelease": false,
              "draft": false,
              "assets": [
                {"name":"xingmu-tv-v0.3.4-e6f5d3dd.apk","size":12919624,"browser_download_url":"https://github.com/x/apk"},
                {"name":"XingmuTV-0.2.0.msi","size":57760138,"browser_download_url":"https://github.com/x/msi"},
                {"name":"XingmuTV-0.2.0.exe","size":58420736,"browser_download_url":"https://github.com/x/exe"},
                {"name":"detail-seed-v1.zip","size":387462,"browser_download_url":"https://github.com/x/zip"}
              ]
            }
        """.trimIndent()
        val release = ReleaseParser.parse(json)
        assertNotNull(release)
        assertEquals("v0.3.4", release.tag)
        assertEquals("0.3.4", release.version)
        assertEquals(4, release.assets.size)
        assertTrue(release.assets.any { it.name == "XingmuTV-0.2.0.msi" })
        val picked = AssetPicker.best(release.assets, windows = true)
        assertEquals("0.2.0", picked?.version)
        assertEquals(AssetKind.MSI, picked?.kind)
    }

    @Test
    fun `malformed json returns null`() {
        assertEquals(null, ReleaseParser.parse("{not json"))
        assertEquals(null, ReleaseParser.parse("""{"assets":[]}"""))
    }
}
