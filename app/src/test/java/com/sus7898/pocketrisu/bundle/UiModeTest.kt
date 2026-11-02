package com.sus7898.pocketrisu.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiModeTest {
    @Test
    fun `bundled only when enabled, versions match and patch sync is known`() {
        val mode = UiMode.decide(enabled = true, bundleVersion = "1.14.0", serverVersion = "1.14.0", disabledForVersion = null, patchSync = false)
        assertEquals(UiMode.Bundled("1.14.0", false), mode)
    }

    @Test
    fun `every other input falls back to the server ui with a reason`() {
        assertTrue(UiMode.decide(false, "1.14.0", "1.14.0", null, true) is UiMode.ServerUi)
        assertTrue(UiMode.decide(true, "", "1.14.0", null, true) is UiMode.ServerUi)
        assertTrue(UiMode.decide(true, "1.14.0", "1.15.0", null, true).describe().contains("mismatch"))
        assertTrue(UiMode.decide(true, "1.14.0", null, null, true).describe().contains("unknown"))
        assertTrue(UiMode.decide(true, "1.14.0", "1.14.0", "1.14.0", true).describe().contains("disabled"))
        assertTrue(UiMode.decide(true, "1.14.0", "1.14.0", null, null).describe().contains("__PATCH_SYNC__"))
    }

    @Test
    fun `hotfix-level server versions may run the bundle when allowed`() {
        // Same minor, newer patch: tolerated only with the switch on.
        assertEquals(UiMode.Bundled("1.16.0", true), UiMode.decide(true, "1.16.0", "1.16.2", null, true, allowHotfixMismatch = true))
        assertTrue(UiMode.decide(true, "1.16.0", "1.16.2", null, true, allowHotfixMismatch = false).describe().contains("mismatch"))
        // Minor bump is never tolerated.
        assertTrue(UiMode.decide(true, "1.16.0", "1.17.0", null, true, allowHotfixMismatch = true).describe().contains("mismatch"))
        // Helper edge cases: leading v, short strings.
        assertTrue(UiMode.sameMinor("v1.16.0", "1.16.3"))
        assertTrue(!UiMode.sameMinor("1.16", "1"))
    }

    @Test
    fun `patch sync flag is read from the server html`() {
        val html = "<html><head><script>globalThis.__NODE__ = true; globalThis.__PATCH_SYNC__ = true</script><meta charset=\"UTF-8\" />"
        assertEquals(true, ServerIndexProbe.parsePatchSync(html))
        assertEquals(false, ServerIndexProbe.parsePatchSync(html.replace("= true</script>", "= false</script>")))
        assertEquals(null, ServerIndexProbe.parsePatchSync("<html><head></head></html>"))
    }

    @Test
    fun `mime types cover what vite emits`() {
        assertEquals("text/javascript", BundleRepository.mimeType("assets/index-abc.js"))
        assertEquals("application/wasm", BundleRepository.mimeType("assets/ort-wasm.wasm"))
        assertEquals("text/html", BundleRepository.mimeType("index.html"))
        assertEquals("application/octet-stream", BundleRepository.mimeType("weird.bin"))
    }
}
