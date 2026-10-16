package com.sus7898.pocketrisu.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sus7898.pocketrisu.log.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves files the web UI produces in-page (chat exports, backups, images) — these arrive as
 * `blob:` URLs, which Android's DownloadManager cannot fetch (docs/TROUBLESHOOTING.md T-16).
 * The page posts {name, mime, data(base64)} to the `PocketRisuAppDownload` message port and the
 * file lands in Downloads/PocketRisu via MediaStore. Bundled mode passes the real file name
 * (patch 030); the generic blob fallback only knows the MIME type.
 */
object DownloadBridge {
    private const val TAG = "Download"
    const val PORT_NAME = "PocketRisuAppDownload"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Payload(val name: String = "", val mime: String = "application/octet-stream", val data: String)

    fun install(webView: WebView, origin: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            AppLog.w(TAG, "WebMessageListener unsupported by this WebView; blob downloads unavailable")
            return
        }
        WebViewCompat.addWebMessageListener(webView, PORT_NAME, setOf(origin)) { view, message, _, _, _ ->
            val text = message.data ?: return@addWebMessageListener
            val payload = runCatching { json.decodeFromString(Payload.serializer(), text) }.getOrElse {
                AppLog.e(TAG, "bad download payload", it); return@addWebMessageListener
            }
            save(view.context, payload)
        }
    }

    /** Generic path for a `blob:` URL from the DownloadListener: read it in-page and post it back. */
    fun requestBlob(webView: WebView, blobUrl: String, mimeHint: String) {
        val name = "pocketrisu-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}" + extensionFor(mimeHint)
        val js = """
            (function(){
              fetch(${jsString(blobUrl)}).then(function(r){return r.blob()}).then(function(b){
                var fr = new FileReader();
                fr.onload = function(){
                  var s = String(fr.result); var i = s.indexOf(',');
                  window.$PORT_NAME.postMessage(JSON.stringify({name: ${jsString(name)}, mime: b.type || ${jsString(mimeHint)}, data: s.substring(i+1)}));
                };
                fr.readAsDataURL(b);
              }).catch(function(e){ console.error('[PocketRisuApp] blob download failed', e) });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        AppLog.i(TAG, "blob download requested mime=$mimeHint")
    }

    private fun save(context: Context, p: Payload) {
        val bytes = runCatching { Base64.decode(p.data, Base64.DEFAULT) }.getOrElse {
            AppLog.e(TAG, "base64 decode failed", it); return
        }
        val name = p.name.ifBlank { "pocketrisu-${System.currentTimeMillis()}" + extensionFor(p.mime) }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, p.mime.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PocketRisu")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            AppLog.e(TAG, "MediaStore insert failed name=$name")
            return
        }
        runCatching {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }.onSuccess {
            AppLog.i(TAG, "saved Downloads/PocketRisu/$name bytes=${bytes.size}")
            Toast.makeText(context, "저장됨: Downloads/PocketRisu/$name", Toast.LENGTH_LONG).show()
        }.onFailure {
            AppLog.e(TAG, "write failed name=$name", it)
            resolver.delete(uri, null, null)
            Toast.makeText(context, "저장 실패: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun extensionFor(mime: String): String = when (mime.lowercase().substringBefore(';').trim()) {
        "image/png" -> ".png"
        "image/jpeg" -> ".jpg"
        "image/webp" -> ".webp"
        "application/json" -> ".json"
        "text/plain" -> ".txt"
        "text/html" -> ".html"
        "application/zip" -> ".zip"
        else -> ".bin"
    }

    private fun jsString(s: String): String = json.encodeToString(kotlinx.serialization.serializer<String>(), s)
}
