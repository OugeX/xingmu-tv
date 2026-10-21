package com.tvbox.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveSeedLoaderTest {

    @Test
    fun `real seed parses channels groups and sorted lines`() {
        val snapshot = LiveSeedLoader.parse(TestAssets.read("live_seed.json"), "test")
        assertTrue(snapshot.channels.size > 200, "channels=${snapshot.channels.size}")
        assertTrue(snapshot.groups.size >= 3, "groups=${snapshot.groups.size}")
        assertEquals(snapshot.channels.size, snapshot.channels.map { it.number }.distinct().size)
        assertEquals(1, snapshot.channels.first().number)

        val cctv1 = snapshot.channels.first { it.id == "cctv1" }
        assertTrue(cctv1.lines.size >= 2, "cctv1 lines=${cctv1.lines.size}")
        assertEquals("1080p", cctv1.lines.first().quality, "最高清晰度应排第一：${cctv1.lines.map { it.quality }}")
        assertTrue(cctv1.lines.last().fallbackOnly, "fallback_only 应排最后")
        assertEquals("央视频道", cctv1.group)
    }

    @Test
    fun `tier ordering prefers 4k then 1080 then 720 and fallback last`() {
        val text = """
            {
              "groups":[{"id":"g1","name":"组1","order":1}],
              "channels":[
                {"id":"c1","name":"测试台","group":"组1","status":"healthy","sources":[
                  {"url":"https://a/720.m3u8","tier":"720p","priority":2},
                  {"url":"https://a/4k.m3u8","tier":"4k","priority":3},
                  {"url":"https://a/1080.m3u8","tier":"1080p","priority":1},
                  {"url":"https://a/err.m3u8","tier":"error","fallback_only":true,"priority":1}
                ]}
              ]
            }
        """.trimIndent()
        val channel = LiveSeedLoader.parse(text, "test").channels.single()
        assertEquals(
            listOf("https://a/4k.m3u8", "https://a/1080.m3u8", "https://a/720.m3u8", "https://a/err.m3u8"),
            channel.lines.map { it.url },
        )
        assertEquals("2160p", channel.lines.first().quality)
    }

    @Test
    fun `missing file represented as empty snapshot with note`() {
        // 通过不存在的资源名触发兜底：这里直接构造一个解析失败文档
        val snapshot = LiveSeedLoader.parse("{bad json", "test")
        assertTrue(snapshot.channels.isEmpty())
        assertTrue(snapshot.note != null)
    }
}
