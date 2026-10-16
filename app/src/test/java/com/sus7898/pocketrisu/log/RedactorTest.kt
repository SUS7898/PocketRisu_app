package com.sus7898.pocketrisu.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpYXQiOjE3MDAwMDAwMDAsImV4cCI6MTcwMDAwMDMwMH0.abcdefghijklmnopqrstuvwxyz0123456789ABCD"

    @Test
    fun `header values are removed`() {
        val out = Redactor.redact("risu-auth: $jwt; Cookie=risu-session=abc123")
        assertEquals("risu-auth: <redacted>", out)
    }

    @Test
    fun `session cookie value is removed`() {
        val out = Redactor.redact("got cookie risu-session=deadbeef00; Path=/")
        assertEquals("got cookie risu-session=<redacted>; Path=/", out)
    }

    @Test
    fun `credential pairs keep the key and drop the value`() {
        assertEquals("password=<redacted>&x=1", Redactor.redact("password=hunter2&x=1"))
        assertEquals("""{"password":"<redacted>"}""", Redactor.redact("""{"password":"hunter2"}"""))
        assertEquals("api_key: <redacted>", Redactor.redact("api_key: sk-123456"))
    }

    @Test
    fun `bare jwt is replaced`() {
        val out = Redactor.redact("token cache $jwt end")
        assertFalse(out.contains("eyJ"))
        assertTrue(out.contains("<redacted>") || out.contains("<jwt>"))
    }

    @Test
    fun `plain operational lines are untouched`() {
        val line = "asset hit key=696e6c61792f abc bytes=12345 ms=3"
        assertEquals(line, Redactor.redact(line))
    }

    @Test
    fun `host masking is case-insensitive`() {
        assertEquals("server: <server> ok", Redactor.maskHost("server: NAS.Example.com ok", "nas.example.com"))
        assertEquals("unchanged", Redactor.maskHost("unchanged", null))
    }
}
