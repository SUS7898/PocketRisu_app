package com.sus7898.pocketrisu.bundle

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.sus7898.pocketrisu.log.AppLog
import java.io.ByteArrayInputStream

/**
 * Serves bundle files for the server origin from inside shouldInterceptRequest.
 * Returns null for anything not in the bundle so the WebView fetches it from the server.
 */
class BundleServer(private val repo: BundleRepository, private val serverHost: String, private val patchSync: Boolean) {
    private val indexHtml: ByteArray by lazy {
        val raw = repo.open("index.html").readBytes().toString(Charsets.UTF_8)
        // Same placement as server.cjs: first child of <head>.
        val injected = raw.replaceFirst("<head>", "<head>" + ServerIndexProbe.injectedScript(patchSync))
        injected.toByteArray(Charsets.UTF_8)
    }

    @Volatile
    var served: Int = 0
        private set

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET") return null
        if (!request.url.host.equals(serverHost, ignoreCase = true)) return null
        val path = request.url.encodedPath ?: return null
        val rel = repo.resolve(path) ?: return null
        return runCatching {
            served++
            if (rel == "index.html") {
                response("text/html", ByteArrayInputStream(indexHtml), "no-store")
            } else {
                // Hashed /assets/* are immutable upstream; keep the same long cache for the WebView.
                val cache = if (rel.startsWith("assets/")) "public, max-age=31536000, immutable" else "no-cache"
                response(BundleRepository.mimeType(rel), repo.open(rel), cache)
            }
        }.onFailure { AppLog.e(TAG, "bundle read failed rel=$rel", it) }.getOrNull()
    }

    private fun response(mime: String, data: java.io.InputStream, cacheControl: String): WebResourceResponse {
        val charset = if (mime.startsWith("text/") || mime == "application/json" || mime == "text/javascript") "utf-8" else null
        return WebResourceResponse(mime, charset, 200, "OK", mapOf("Cache-Control" to cacheControl, "X-PocketRisu-App" to "bundle"), data)
    }

    companion object {
        private const val TAG = "Bundle"
    }
}
