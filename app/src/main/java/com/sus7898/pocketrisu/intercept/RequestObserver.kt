package com.sus7898.pocketrisu.intercept

import android.webkit.WebResourceRequest
import com.sus7898.pocketrisu.log.AppLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * Milestone 1 observation of the API routes the cache layer will handle later (docs/DESIGN.md §3.2).
 * Pass-through only — nothing is fetched here — but the exported log now shows WHAT was read and WHEN:
 *   I/Intercept read key=database/database.bin           (bootstrap)
 *   I/Intercept read key=assets/ 5f2a…                   (character/module asset bytes)
 *   I/Intercept chat-content chaId=ab12cd34 idx=0 delta=true
 *   D/Intercept asset inlay/ 9c1e…  + an I summary every 50 asset requests
 * Keys and ids are opaque identifiers; names and chat text never reach the log (docs/LOGGING.md §3).
 */
object RequestObserver {
    private const val TAG = "Intercept"
    private val assetCount = AtomicInteger()
    private val assetByPrefix = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    fun observe(request: WebResourceRequest) {
        val path = request.url.encodedPath ?: return
        if (request.method != "GET") {
            observeWrite(request, path)
            return
        }
        when {
            path == "/api/read" -> {
                val key = decodeHexKey(request.requestHeaders["file-path"])
                AppLog.i(TAG, "read key=${shortKey(key)}")
            }
            path.startsWith("/api/chat-content/") -> {
                val parts = path.removePrefix("/api/chat-content/").split('/')
                val delta = request.requestHeaders.containsKey("x-chat-base-count")
                AppLog.i(TAG, "chat-content chaId=${parts.getOrNull(0)?.take(8)} idx=${parts.getOrNull(1)} delta=$delta")
            }
            path.startsWith("/api/asset/") -> {
                val key = decodeHexKey(path.removePrefix("/api/asset/"))
                val prefix = key?.substringBefore('/', "?") ?: "?"
                AppLog.d(TAG, "asset ${shortKey(key)}")
                assetByPrefix.getOrPut(prefix) { AtomicInteger() }.incrementAndGet()
                val n = assetCount.incrementAndGet()
                if (n % 50 == 0) {
                    AppLog.i(TAG, "asset requests n=$n " + assetByPrefix.entries.joinToString(" ") { "${it.key}=${it.value}" })
                }
            }
            path.startsWith("/api/model-jobs") -> AppLog.i(TAG, "model-jobs ${path.removePrefix("/api/model-jobs").take(40)}")
            path.startsWith("/proxy2") -> AppLog.i(TAG, "proxy2 GET (image/aux provider request)")
        }
    }

    /**
     * Writes are what make the single-threaded server busy (T-7): a full database.bin upload or a
     * patch right after boot can stall every later request. Bodies are invisible here; the key
     * header and the route are enough to see the pattern.
     */
    private fun observeWrite(request: WebResourceRequest, path: String) {
        val m = request.method
        when {
            path == "/api/write" -> AppLog.i(TAG, "$m write key=${shortKey(decodeHexKey(request.requestHeaders["file-path"]))} len=${request.requestHeaders["content-length"] ?: "?"}")
            path == "/api/patch" -> AppLog.i(TAG, "$m patch len=${request.requestHeaders["content-length"] ?: "?"}")
            path.startsWith("/api/chat-content/") -> {
                val parts = path.removePrefix("/api/chat-content/").split('/')
                AppLog.i(TAG, "$m chat-content save chaId=${parts.getOrNull(0)?.take(8)} idx=${parts.getOrNull(1)} delta=${request.requestHeaders.containsKey("x-chat-base-count")}")
            }
            path == "/api/db/flush" || path.startsWith("/api/backup/") || path.startsWith("/api/db/") ->
                AppLog.i(TAG, "$m ${path.take(40)}")
            path.startsWith("/api/model-jobs") -> AppLog.i(TAG, "$m model-jobs ${path.removePrefix("/api/model-jobs").take(40)}")
            path.startsWith("/proxy") -> AppLog.i(TAG, "$m ${path.take(12)} (provider request)")
        }
    }

    /** `file-path` / asset path values are hex(utf-8 key); unknown or malformed input yields null. */
    fun decodeHexKey(hex: String?): String? {
        val h = hex?.trim()?.takeIf { it.length % 2 == 0 && it.isNotEmpty() } ?: return null
        return runCatching {
            ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }.toString(Charsets.UTF_8)
        }.getOrNull()
    }

    /** "inlay/abcdef0123456789..." → "inlay/ abcdef012345…" — prefix kept, id truncated. */
    fun shortKey(key: String?): String {
        if (key == null) return "?"
        val slash = key.indexOf('/')
        if (slash < 0) return key.take(24)
        val prefix = key.substring(0, slash + 1)
        val rest = key.substring(slash + 1)
        return if (rest == "database.bin") key else "$prefix ${rest.take(12)}${if (rest.length > 12) "…" else ""}"
    }
}
