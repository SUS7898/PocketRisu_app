package com.sus7898.pocketrisu.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCodeTest {
    @Test
    fun `release and dev builds follow the gradle formula`() {
        assertEquals(1_020_300, VersionCode.from("1.2.3"))
        assertEquals(1_020_300, VersionCode.from("v1.2.3"))
        assertEquals(1_020_307, VersionCode.from("1.2.3-dev.7+abc1234"))
        assertEquals(10_000, VersionCode.from("0.1.0-local"))
        assertEquals(0, VersionCode.from("nightly"))
    }

    @Test
    fun `ordering survives alternating nightly and release builds`() {
        val r010 = VersionCode.from("0.1.0")
        val dev = VersionCode.from("0.1.0-dev.12+abc")
        val r011 = VersionCode.from("0.1.1")
        val dev2 = VersionCode.from("0.1.1-dev.1+def")
        val r020 = VersionCode.from("0.2.0")
        assertTrue(r010 < dev && dev < r011 && r011 < dev2 && dev2 < r020)
        // dev count is capped so it can never spill into the next patch number
        assertTrue(VersionCode.from("0.1.0-dev.500+x") < r011)
    }
}
