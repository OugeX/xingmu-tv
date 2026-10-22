package com.xingmu.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release JSON 解析与说明清理（UPDATE_SPEC §1/§3）。 */
class GitHubReleaseParserTest {

    private val json = """
        {
          "tag_name": "v0.3.4",
          "prerelease": false,
          "html_url": "https://github.com/OugeX/xingmu-tv/releases/tag/v0.3.4",
          "body": "## 星幕 TV v0.3.4\n\n- 新增应用内更新\n- 修复若干问题\n\n```\nadb install foo.apk\n```\n",
          "assets": [
            {"name": "xingmu-tv-v0.3.4-abc12345.apk", "size": 12900000, "browser_download_url": "https://github.com/download/a.apk"},
            {"name": "xingmu-tv-v0.3.4-r8-def67890.apk", "size": 3000000, "browser_download_url": "https://github.com/download/r.apk"},
            {"name": "detail-seed-v1.zip", "size": 387462, "browser_download_url": "https://github.com/download/d.zip"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses tag assets and url`() {
        val release = GitHubReleaseParser.parse(json)
        assertNotNull(release)
        release!!
        assertEquals("v0.3.4", release.tag)
        assertEquals("0.3.4", release.version)
        assertEquals(3, release.assets.size)
        assertTrue(release.pageUrl.contains("/releases/tag/v0.3.4"))
    }

    @Test
    fun `invalid json returns null`() {
        assertNull(GitHubReleaseParser.parse("{not json"))
        assertNull(GitHubReleaseParser.parse("""{"body":"no tag"}"""))
    }

    @Test
    fun `notes strip code blocks and markdown markers`() {
        val notes = ReleaseNotes.plain(
            "## 星幕 TV v0.3.4\n\n- 新增应用内更新\n- 修复若干问题\n\n```\nadb install foo.apk\n```\n[详情](https://x.y)",
        )
        assertTrue(notes.contains("新增应用内更新"))
        assertTrue(notes.contains("详情"))
        assertTrue(!notes.contains("adb install"))
        assertTrue(!notes.contains("```"))
        assertTrue(!notes.contains("##"))
    }

    @Test
    fun `notes truncate to 500 chars`() {
        val long = "字".repeat(900)
        val plain = ReleaseNotes.plain(long)
        assertEquals(501, plain.length) // 500 + 省略号
        assertTrue(plain.endsWith("…"))
    }
}
