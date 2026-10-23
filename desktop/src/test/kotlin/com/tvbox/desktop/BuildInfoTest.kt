package com.tvbox.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoTest {

    @Test
    fun `version is read from build generated resource`() {
        assertEquals("0.2.0", BuildInfo.version)
    }
}
