package com.sus7898.pocketrisu.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {
    @Test
    fun `https url is normalised to its origin`() {
        assertEquals("https://nas.example.com", AppSettings.normalizeServerUrl(" https://nas.example.com/ ", allowHttp = false))
        assertEquals("https://nas.example.com:8443", AppSettings.normalizeServerUrl("https://nas.example.com:8443/some/path?x=1", allowHttp = false))
    }

    @Test
    fun `http is rejected unless explicitly allowed`() {
        assertNull(AppSettings.normalizeServerUrl("http://nas.example.com", allowHttp = false))
        assertEquals("http://10.0.2.2:6001", AppSettings.normalizeServerUrl("http://10.0.2.2:6001", allowHttp = true))
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(AppSettings.normalizeServerUrl("nas", allowHttp = false))
        assertNull(AppSettings.normalizeServerUrl("", allowHttp = false))
    }
}
