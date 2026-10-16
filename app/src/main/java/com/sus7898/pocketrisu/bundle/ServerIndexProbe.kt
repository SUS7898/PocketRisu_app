package com.sus7898.pocketrisu.bundle

import com.sus7898.pocketrisu.log.AppLog
import com.sus7898.pocketrisu.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * The server injects `globalThis.__NODE__ = true; globalThis.__PATCH_SYNC__ = <bool>` into index.html
 * (server.cjs `app.get('/')`). __PATCH_SYNC__ follows a server environment variable, so the bundled
 * index.html must copy the live value on every launch (docs/DESIGN.md §4.1, R8).
 */
object ServerIndexProbe {
    private const val TAG = "Bundle"
    private val patchSyncRegex = Regex("""__PATCH_SYNC__\s*=\s*(true|false)""")

    fun parsePatchSync(html: String): Boolean? = patchSyncRegex.find(html)?.groupValues?.get(1)?.toBoolean()

    suspend fun fetchPatchSync(baseUrl: String): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$baseUrl/").header("User-Agent", Http.userAgentSuffix).build()
            Http.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) error("HTTP ${res.code}")
                // Only the <head> matters; cap the read so a huge page cannot stall the launch.
                val head = res.body!!.source().let { src -> src.request(64 * 1024); src.buffer.readUtf8(minOf(src.buffer.size, 64 * 1024)) }
                parsePatchSync(head)
            }
        }.onFailure { AppLog.w(TAG, "server index probe failed", it) }.getOrNull()
    }

    /** Builds the script the server would have injected. */
    fun injectedScript(patchSync: Boolean): String =
        "<script>globalThis.__NODE__ = true; globalThis.__PATCH_SYNC__ = $patchSync; globalThis.__POCKETRISU_APP_BUNDLE__ = true</script>"
}
