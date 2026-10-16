package com.sus7898.pocketrisu.bundle

import android.content.Context
import android.content.res.AssetManager
import com.sus7898.pocketrisu.BuildConfig
import com.sus7898.pocketrisu.log.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

/**
 * Read access to the PocketRisu UI build packaged under assets/bundle/ by scripts/build-frontend.sh.
 * The file list from BUNDLE.json decides what the interceptor serves locally; anything else on the
 * server origin passes through to the server unchanged (docs/DESIGN.md §4.1).
 */
class BundleRepository(context: Context) {
    @Serializable
    data class Manifest(
        val upstreamVersion: String,
        val upstreamSha: String = "",
        val patches: Int = 0,
        val builtAt: String = "",
        val fileCount: Int = 0,
        val files: List<String> = emptyList(),
    )

    private val assets: AssetManager = context.assets
    val manifest: Manifest? = runCatching {
        assets.open("$ROOT/BUNDLE.json").bufferedReader().use { Json { ignoreUnknownKeys = true }.decodeFromString(Manifest.serializer(), it.readText()) }
    }.onFailure {
        if (BuildConfig.BUNDLED_UPSTREAM_VERSION.isNotEmpty()) AppLog.e(TAG, "BUNDLE.json unreadable although build declared v${BuildConfig.BUNDLED_UPSTREAM_VERSION}", it)
    }.getOrNull()

    private val files: Set<String> = manifest?.files?.toHashSet() ?: emptySet()

    val available: Boolean get() = manifest != null && "index.html" in files
    val version: String? get() = manifest?.upstreamVersion

    /** Request path ("/assets/x.js") → bundle-relative path, or null when the bundle has no such file. */
    fun resolve(path: String): String? {
        val rel = when {
            path == "/" || path == "/index.html" -> "index.html"
            else -> path.removePrefix("/")
        }
        return rel.takeIf { it in files }
    }

    fun open(rel: String): InputStream = assets.open("$ROOT/$rel")

    companion object {
        private const val TAG = "Bundle"
        const val ROOT = "bundle"

        fun mimeType(rel: String): String = when (rel.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "wasm" -> "application/wasm"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "txt" -> "text/plain"
            "lua" -> "text/plain"
            "7z" -> "application/x-7z-compressed"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "webm" -> "video/webm"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}
