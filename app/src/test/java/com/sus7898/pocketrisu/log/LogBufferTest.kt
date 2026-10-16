package com.sus7898.pocketrisu.log

import org.junit.Assert.assertEquals
import org.junit.Test

class LogBufferTest {
    @Test
    fun `keeps only the newest lines`() {
        val buf = LogBuffer(3)
        listOf("a", "b", "c", "d").forEach(buf::add)
        assertEquals(listOf("b", "c", "d"), buf.snapshot())
        assertEquals(3, buf.size)
    }

    @Test
    fun `clear empties the buffer`() {
        val buf = LogBuffer(2)
        buf.add("x")
        buf.clear()
        assertEquals(emptyList<String>(), buf.snapshot())
    }
}
