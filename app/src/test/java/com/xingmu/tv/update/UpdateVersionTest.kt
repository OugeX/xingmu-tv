package com.xingmu.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UPDATE_SPEC §1：语义化版本比较。 */
class UpdateVersionTest {

    @Test
    fun `patch bump is newer`() {
        assertTrue(UpdateVersion.isNewer("0.3.3", "0.3.4"))
        assertFalse(UpdateVersion.isNewer("0.3.4", "0.3.3"))
    }

    @Test
    fun `multi digit segments compare numerically`() {
        assertTrue(UpdateVersion.isNewer("v0.3.9", "v0.3.10"))
        assertFalse(UpdateVersion.isNewer("v0.3.10", "v0.3.9"))
        assertEquals(1, UpdateVersion.compare("v0.3.10", "v0.3.9"))
    }

    @Test
    fun `same version returns false`() {
        assertFalse(UpdateVersion.isNewer("0.3.4", "0.3.4"))
        assertFalse(UpdateVersion.isNewer("v0.3.4", "0.3.4"))
        assertFalse(UpdateVersion.isNewer("0.3", "0.3.0"))
    }

    @Test
    fun `suffix is ignored`() {
        assertEquals("0.3.4", UpdateVersion.core("v0.3.4-beta"))
        assertEquals("0.3.4", UpdateVersion.core("V0.3.4"))
        assertFalse(UpdateVersion.isNewer("0.3.4", "v0.3.4-beta"))
    }

    @Test
    fun `asset name match uses numeric boundaries`() {
        assertTrue(UpdateVersion.assetMatches("xingmu-tv-v0.3.4-ad916cbc.apk", "0.3.4"))
        assertTrue(UpdateVersion.assetMatches("xingmu-tv-v0.3.4-r8-23e77dfc.apk", "v0.3.4"))
        assertFalse(UpdateVersion.assetMatches("app-debug.apk", "0.3.4"))
        assertFalse(UpdateVersion.assetMatches("xingmu-tv-v0.3.40-abc.apk", "0.3.4"))
    }
}
