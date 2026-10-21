package com.tvbox.desktop.player

import com.tvbox.desktop.data.PlayLine
import com.tvbox.desktop.data.PlaybackRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLadderTest {

    private val liveRequest = PlaybackRequest(
        title = "CCTV-1",
        subtitle = "直播",
        isLive = true,
        lines = listOf(
            PlayLine("线路 1", "https://a/1080.m3u8", "1080p"),
            PlayLine("线路 2", "https://a/720.m3u8", "720p"),
        ),
    )

    @Test
    fun `live ladder degrades hardware to software then lower quality`() {
        val attempts = PlaybackLadder.build(liveRequest, hardwareDecode = true, maxQuality = "auto")
        assertEquals(4, attempts.size)
        assertEquals("1080p", attempts[0].quality)
        assertFalse(attempts[0].softwareDecode)
        assertEquals("1080p", attempts[1].quality)
        assertTrue(attempts[1].softwareDecode)
        assertEquals("720p", attempts[2].quality)
        assertFalse(attempts[2].softwareDecode)
        assertEquals("720p", attempts[3].quality)
        assertTrue(attempts[3].softwareDecode)
        assertTrue(attempts.all { it.url.startsWith("https://a/") })
    }

    @Test
    fun `hardware disabled skips hardware attempts`() {
        val attempts = PlaybackLadder.build(liveRequest, hardwareDecode = false, maxQuality = "auto")
        assertEquals(2, attempts.size)
        assertTrue(attempts.all { it.softwareDecode })
    }

    @Test
    fun `quality ceiling removes 1080p line`() {
        val attempts = PlaybackLadder.build(liveRequest, hardwareDecode = true, maxQuality = "720p")
        assertEquals(2, attempts.size)
        assertEquals("720p", attempts.first().quality)
    }

    @Test
    fun `vod ladder keeps alternative play_from lines`() {
        val request = PlaybackRequest(
            title = "影片 · 第1集",
            subtitle = "点播",
            isLive = false,
            lines = listOf(
                PlayLine("bfzym3u8", "https://a/1.m3u8"),
                PlayLine("bfzym3u8b", "https://b/1.m3u8"),
            ),
        )
        val attempts = PlaybackLadder.build(request, hardwareDecode = true, maxQuality = "auto")
        assertEquals(listOf("bfzym3u8", "bfzym3u8", "bfzym3u8b", "bfzym3u8b"), attempts.map { it.lineLabel })
        assertFalse(attempts[0].softwareDecode)
        assertTrue(attempts[1].softwareDecode)
    }
}
