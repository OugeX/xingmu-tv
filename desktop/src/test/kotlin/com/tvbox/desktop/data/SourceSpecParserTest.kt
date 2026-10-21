package com.tvbox.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceSpecParserTest {

    private val minimal = """
        {"schema":"tvapp.source.v1.1","kind":"maccms","id":"demo","name":"演示源",
         "baseUrl":"https://example.com/api.php/provide/vod/"}
    """.trimIndent()

    @Test
    fun `builtin bundle passes validation`() {
        val text = TestAssets.read("sources.builtin.json")
        val report = SourceSpecParser.parseText(text, "builtin")
        assertTrue(report.ok, "内置源应通过校验：${report.issues}")
        assertTrue(report.sources.size >= 5, "内置源数量=${report.sources.size}")
        assertEquals(0, report.issues.size)
        assertTrue(report.sources.any { it.config.id == "bfzy1" })
        assertEquals(SourceKind.MACCMS, report.sources.first { it.config.id == "bfzy1" }.config.kind)
    }

    @Test
    fun `minimal maccms source is valid`() {
        val report = SourceSpecParser.parseText(minimal, "test")
        assertTrue(report.ok)
        assertEquals("demo", report.sources.single().config.id)
        assertEquals("https://example.com/api.php/provide/vod/", report.sources.single().config.baseUrl)
    }

    @Test
    fun `bad id is rejected with E_SCHEMA`() {
        val text = minimal.replace("\"demo\"", "\"Bad ID!\"")
        val report = SourceSpecParser.parseText(text, "test")
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "E_SCHEMA" && it.path.contains("id") }, report.issues.toString())
    }

    @Test
    fun `unknown field is rejected`() {
        val report = SourceSpecParser.parseText(minimal.replace("}", ",\"evil\":1}"), "test")
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "E_SCHEMA" && it.message.contains("未知字段") })
    }

    @Test
    fun `bad url scheme is E_URL`() {
        val report = SourceSpecParser.parseText(minimal.replace("https://example.com", "file:///etc/passwd"), "test")
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "E_URL" || it.code == "E_SCHEMA" })
    }

    @Test
    fun `m3u must choose url or inline`() {
        val text = """
            {"schema":"tvapp.source.v1","kind":"m3u","id":"m1","name":"m",
             "url":"https://e.com/a.m3u","inline":"#EXTM3U"}
        """.trimIndent()
        val report = SourceSpecParser.parseText(text, "test")
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "E_FIELD" && it.message.contains("二选一") }, report.issues.toString())
    }

    @Test
    fun `nested bundle is rejected`() {
        val text = """
            {"schema":"tvapp.source.v1","kind":"bundle","id":"b1","name":"b","sources":[
              {"schema":"tvapp.source.v1","kind":"bundle","id":"b2","name":"b2","sources":[$minimal]}
            ]}
        """.trimIndent()
        val report = SourceSpecParser.parseText(text, "test")
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "E_NESTED" })
    }

    @Test
    fun `duplicate ids in bundle are rejected`() {
        val text = """
            {"schema":"tvapp.source.v1","kind":"bundle","id":"b1","name":"b","sources":[$minimal,$minimal]}
        """.trimIndent()
        val report = SourceSpecParser.parseText(text, "test")
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "E_ID" && it.message.contains("id 重复") }, report.issues.toString())
    }

    @Test
    fun `adblock blocking own endpoint is rejected`() {
        val text = """
            {"schema":"tvapp.source.v1.1","kind":"maccms","id":"ad1","name":"ad",
             "baseUrl":"https://ads.example.com/api.php/provide/vod/",
             "adBlock":{"enabled":true,"domains":["ads.example.com"]}}
        """.trimIndent()
        val report = SourceSpecParser.parseText(text, "test")
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "E_ADBLOCK" }, report.issues.toString())
    }

    @Test
    fun `channel duplicates are rejected`() {
        val text = """
            {"schema":"tvapp.source.v1.1","kind":"m3u","id":"m2","name":"m",
             "url":"https://e.com/a.m3u","channels":[
               {"name":"CCTV-1","url":"https://e.com/a.m3u8"},
               {"name":"cctv-1 ","url":"https://e.com/b.m3u8"}
             ]}
        """.trimIndent()
        val report = SourceSpecParser.parseText(text, "test")
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "E_CHANNEL" && it.message.contains("重复") })
    }

    @Test
    fun `invalid regex is rejected`() {
        val text = """
            {"schema":"tvapp.source.v1.1","kind":"m3u","id":"m3","name":"m",
             "url":"https://e.com/a.m3u","filters":{"excludeNameRegex":"(["}}
        """.trimIndent()
        val report = SourceSpecParser.parseText(text, "test")
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "E_REGEX" })
    }

    @Test
    fun `bad v11 enums are rejected`() {
        val report = SourceSpecParser.parseText(minimal.replace("}", ",\"maxQuality\":\"4k\"}"), "test")
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "E_FIELD" && it.path.contains("maxQuality") })
    }

    @Test
    fun `broken json is E_JSON`() {
        val report = SourceSpecParser.parseText("{not json", "test")
        assertFalse(report.ok)
        assertEquals("E_JSON", report.issues.first().code)
        assertNotNull(report.issues.first().message)
    }
}
