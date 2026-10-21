package com.tvbox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t28：封面「thumb 优先 / 回退」决策（纯函数，无需设备）。
 */
class PosterPolicyTest {

    private val pic = "https://img.example.com/big/169066.jpg"
    private val thumb = "https://img.example.com/thumb/169066.jpg"

    @Test
    fun prefersDedicatedThumbWhenPresent() {
        val plan = PosterPolicy.plan(pic, thumb, 320, 450)
        assertEquals(thumb, plan.initialUrl)
        assertEquals(pic, plan.fullUrl)
        assertTrue("有独立缩略图 → 首帧按卡片尺寸请求", plan.initialWidth == 320 && plan.initialHeight == 450)
        assertTrue("停稳后仍可升级到原图", plan.canUpgrade)
        assertTrue(plan.hasImage)
    }

    @Test
    fun fallsBackToPicWithHalfSizeWhenThumbMissing() {
        val plan = PosterPolicy.plan(pic, "", 320, 450)
        assertEquals("没有 thumb 时首帧用 vod_pic", pic, plan.initialUrl)
        assertEquals("半尺寸先出低清", 160, plan.initialWidth)
        assertEquals(225, plan.initialHeight)
        assertEquals(320, plan.fullWidth)
        assertEquals(450, plan.fullHeight)
        assertTrue("同 URL 但尺寸变大 → 仍值得升级", plan.canUpgrade)
    }

    @Test
    fun fallsBackToThumbWhenPicMissing() {
        val plan = PosterPolicy.plan("", thumb, 320, 450)
        assertEquals(thumb, plan.initialUrl)
        assertEquals(thumb, plan.fullUrl)
        assertTrue(plan.hasImage)
        // 只有一个 URL 时仍是「半尺寸 → 原尺寸」两段式，所以升级依然有意义
        assertTrue(plan.canUpgrade)
        assertEquals(160, plan.initialWidth)
        assertEquals(320, plan.fullWidth)
    }

    @Test
    fun noImageWhenBothBlank() {
        val plan = PosterPolicy.plan("  ", "", 320, 450)
        assertFalse(plan.hasImage)
        assertFalse(plan.canUpgrade)
        assertEquals("", plan.initialUrl)
    }

    @Test
    fun halfSizeNeverZero() {
        assertEquals(1, PosterPolicy.halfSize(0))
        assertEquals(1, PosterPolicy.halfSize(1))
        assertEquals(2, PosterPolicy.halfSize(5))
        val plan = PosterPolicy.plan(pic, "", 1, 1)
        assertEquals(1, plan.initialWidth)
        assertEquals(1, plan.initialHeight)
    }

    @Test
    fun scrollingMapsToInexactPrecision() {
        assertFalse("滚动中 → INEXACT（允许低清缩放）", PosterPolicy.precisionFor(scrolling = true))
        assertTrue("停稳 → EXACT", PosterPolicy.precisionFor(scrolling = false))
    }

    @Test
    fun whitespaceThumbTreatedAsMissing() {
        val plan = PosterPolicy.plan(pic, "   ", 200, 300)
        assertEquals(pic, plan.initialUrl)
        assertEquals(100, plan.initialWidth)
    }
}
