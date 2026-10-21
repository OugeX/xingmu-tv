package com.tvbox.desktop.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacCmsParserTest {

    private val source = SourceConfig(
        id = "test",
        name = "测试源",
        kind = SourceKind.MACCMS,
        baseUrl = "https://example.com/api.php/provide/vod/",
    )

    private fun listJson(): String {
        val list = JSONArray()
        list.put(
            JSONObject()
                .put("vod_id", 169028)
                .put("vod_name", "流浪地球")
                .put("vod_pic", "https://img.example.com/a.jpg")
                .put("vod_remarks", "HD")
                .put("vod_year", "2019")
                .put("type_id", 20)
                .put("type_name", "科幻片")
                .put("vod_score", "8.5"),
        )
        list.put(JSONObject().put("vod_id", 2).put("vod_name", ""))
        return JSONObject().put("code", 1).put("list", list).toString()
    }

    private fun detailJson(): String {
        val obj = JSONObject()
            .put("vod_id", "169028")
            .put("vod_name", "流浪地球")
            .put("vod_pic", "https://img.example.com/a.jpg")
            .put("vod_pic_slide", "https://img.example.com/slide.jpg")
            .put("vod_score", "8.5")
            .put("vod_year", "2019")
            .put("vod_area", "中国大陆")
            .put("vod_lang", "国语")
            .put("vod_duration", "125分钟")
            .put("vod_remarks", "HD")
            .put("vod_actor", "吴京,屈楚萧")
            .put("vod_director", "郭帆")
            .put("vod_content", "<p>太阳即将毁灭<br/>人类开启流浪地球计划</p>")
            .put("type_name", "科幻片")
            .put("vod_play_from", "bfzym3u8${'$'}${'$'}${'$'}bfzym3u8b")
            .put(
                "vod_play_url",
                "HD${'$'}https://v.example.com/1.m3u8#第2集${'$'}https://v.example.com/2.m3u8" +
                    "${'$'}${'$'}${'$'}HD${'$'}https://b.example.com/1.m3u8#第2集${'$'}https://b.example.com/2.m3u8",
            )
        return JSONObject().put("code", 1).put("list", JSONArray().put(obj)).toString()
    }

    @Test
    fun `parse list maps fields and filters empty names`() {
        val items = MacCmsParser.parseList(listJson(), source)
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("169028", item.vodId)
        assertEquals("流浪地球", item.name)
        assertEquals("HD", item.remarks)
        assertEquals("科幻片", item.typeName)
        assertEquals("test", item.sourceId)
        assertEquals(listOf(VodRef("test", "测试源", "169028")), item.refs)
    }

    @Test
    fun `parse detail splits play groups and episodes`() {
        val detail = MacCmsParser.parseDetail(detailJson(), source, "169028")
        assertEquals("流浪地球", detail.name)
        assertEquals(listOf("bfzym3u8", "bfzym3u8b"), detail.playFroms)
        val primary = detail.episodesByFrom.getValue("bfzym3u8")
        assertEquals(2, primary.size)
        assertEquals("HD", primary[0].title)
        assertEquals("第2集", primary[1].title)
        assertEquals("https://v.example.com/1.m3u8", primary[0].url)
        assertEquals("https://b.example.com/1.m3u8", detail.episodesByFrom.getValue("bfzym3u8b")[0].url)
        assertEquals("太阳即将毁灭\n人类开启流浪地球计划", detail.content)
        assertEquals("https://img.example.com/slide.jpg", detail.slidePic)
    }

    @Test
    fun `non http episode urls are dropped`() {
        val obj = JSONObject()
            .put("vod_id", "1")
            .put("vod_name", "x")
            .put("vod_play_from", "line")
            .put("vod_play_url", "1${'$'}ftp://bad/1.m3u8#2${'$'}https://good/2.m3u8")
        val json = JSONObject().put("code", 1).put("list", JSONArray().put(obj)).toString()
        val detail = MacCmsParser.parseDetail(json, source, "1")
        assertEquals(listOf("https://good/2.m3u8"), detail.episodesByFrom.getValue("line").map { it.url })
    }

    @Test
    fun `non success code throws`() {
        val thrown = runCatching { MacCmsParser.parseList("""{"code":0,"msg":"offline"}""", source) }.exceptionOrNull()
        assertTrue(thrown is MacCmsException)
        assertEquals("SOURCE_OFFLINE", (thrown as MacCmsException).code)
    }
}
