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
    fun `provider api keys are removed wherever they appear`() {
        val openai = "Incorrect API key provided: sk-proj-AbCdEfGhIjKlMnOpQrStUvWx123"
        assertFalse(Redactor.redact(openai).contains("AbCdEfGh"))
        val google = "request to ...?key=AIzaSyA1B2C3D4E5F6G7H8I9J0K1L2M3N4O5P6Q failed"
        assertFalse(Redactor.redact(google).contains("AIzaSy"))
        assertEquals("x-goog-api-key: <redacted>", Redactor.redact("x-goog-api-key: AIzaSyA1B2C3D4E5F6G7H8I9J0K1L2M3N4O5P6Q"))
        // Ordinary words containing "sk-" are not keys.
        assertEquals("task-runner started", Redactor.redact("task-runner started"))
    }

    @Test
    fun `service account private key is removed, even when truncated`() {
        val full = """{"type":"service_account","private_key":"-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----\n","client_email":"svc@x.iam"}"""
        val out = Redactor.redact(full)
        assertFalse(out.contains("MIIEvQ"))
        assertTrue(out.contains("<private-key>"))
        assertTrue(out.contains("svc@x.iam"))
        val truncated = """parse failed: "private_key":"-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC"""
        assertFalse(Redactor.redact(truncated).contains("MIIEvQ"))
    }

    @Test
    fun `prefixed credential names and bare bearer tokens are removed`() {
        assertEquals("refresh_token=<redacted>&a=1", Redactor.redact("refresh_token=1//0gAbCdEf&a=1"))
        assertEquals("""{"client_secret":"<redacted>"}""", Redactor.redact("""{"client_secret":"GOCSPX-abc123"}"""))
        assertFalse(Redactor.redact("retry with Bearer ya29.a0AfH6SMBxyz0123456789abc").contains("ya29"))
    }

    @Test
    fun `host masking is case-insensitive`() {
        assertEquals("server: <server> ok", Redactor.maskHost("server: NAS.Example.com ok", "nas.example.com"))
        assertEquals("unchanged", Redactor.maskHost("unchanged", null))
    }
}
