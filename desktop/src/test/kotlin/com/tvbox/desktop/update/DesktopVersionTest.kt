package com.tvbox.desktop.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopVersionTest {

    @Test
    fun `core strips v prefix and prerelease suffix`() {
        assertEquals("0.2.0", DesktopVersion.core("v0.2.0"))
        assertEquals("0.2.0", DesktopVersion.core("V0.2.0"))
        assertEquals("0.2.0", DesktopVersion.core("0.2.0-beta"))
        assertEquals("0.3.4", DesktopVersion.core("v0.3.4-r8"))
        assertEquals("", DesktopVersion.core("release"))
    }

    @Test
    fun `compare is numeric and pads missing segments`() {
        assertEquals(0, DesktopVersion.compare("1.0", "1.0.0"))
        assertEquals(0, DesktopVersion.compare("v0.2.0", "0.2"))
        assertTrue(DesktopVersion.compare("0.3.10", "0.3.9") > 0)
        assertTrue(DesktopVersion.compare("0.2.0", "0.2.1") < 0)
        assertTrue(DesktopVersion.compare("0.10", "0.9") > 0)
        assertEquals(0, DesktopVersion.compare("", "0"))
    }

    @Test
    fun `isNewer only true for strict newer`() {
        assertTrue(DesktopVersion.isNewer("0.2.0", "0.3.4"))
        assertTrue(DesktopVersion.isNewer("0.2.0", "0.2.1"))
        assertFalse(DesktopVersion.isNewer("0.2.0", "0.2.0"))
        assertFalse(DesktopVersion.isNewer("0.2.0", "0.1.9"))
    }
}
