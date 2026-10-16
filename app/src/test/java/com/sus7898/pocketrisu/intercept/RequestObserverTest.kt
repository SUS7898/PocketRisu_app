package com.sus7898.pocketrisu.intercept

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestObserverTest {
    private fun hex(s: String) = s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    @Test
    fun `hex keys decode and malformed input is rejected`() {
        assertEquals("database/database.bin", RequestObserver.decodeHexKey(hex("database/database.bin")))
        assertEquals("inlay/abc", RequestObserver.decodeHexKey(hex("inlay/abc").uppercase()))
        assertNull(RequestObserver.decodeHexKey("abc")) // odd length
        assertNull(RequestObserver.decodeHexKey(null))
        assertNull(RequestObserver.decodeHexKey("zz"))
    }

    @Test
    fun `short keys keep the prefix and truncate the id`() {
        assertEquals("database/database.bin", RequestObserver.shortKey("database/database.bin"))
        assertEquals("inlay/ 0123456789ab…", RequestObserver.shortKey("inlay/0123456789abcdef"))
        assertEquals("assets/ short", RequestObserver.shortKey("assets/short"))
        assertEquals("?", RequestObserver.shortKey(null))
    }
}
