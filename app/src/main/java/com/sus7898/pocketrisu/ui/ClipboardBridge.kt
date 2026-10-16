package com.sus7898.pocketrisu.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sus7898.pocketrisu.log.AppLog

/**
 * `navigator.clipboard.writeText` is denied inside WebView ("Write permission denied", T-17), so
 * PocketRisu's copy buttons silently fail. A document-start script replaces it with a call into
 * the `PocketRisuAppClipboard` message port; the text lands in the system clipboard. The text
 * itself is never logged.
 */
object ClipboardBridge {
    private const val TAG = "Clipboard"
    const val PORT_NAME = "PocketRisuAppClipboard"

    fun install(webView: WebView, origin: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            AppLog.w(TAG, "WebView lacks WEB_MESSAGE_LISTENER/DOCUMENT_START_SCRIPT; clipboard bridge unavailable")
            return
        }
        WebViewCompat.addWebMessageListener(webView, PORT_NAME, setOf(origin)) { view, message, _, _, _ ->
            val text = message.data ?: return@addWebMessageListener
            copy(view.context, text)
        }
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            """
            (function(){
              try {
                var port = window.$PORT_NAME;
                if (!port || !navigator.clipboard) return;
                var writeText = function(text){ port.postMessage(String(text)); return Promise.resolve(); };
                Object.defineProperty(navigator.clipboard, 'writeText', { value: writeText, configurable: true });
              } catch (e) { console.warn('[PocketRisuApp] clipboard bridge install failed', e); }
            })();
            """.trimIndent(),
            setOf(origin),
        )
    }

    private fun copy(context: Context, text: String) {
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("PocketRisu", text))
        }.onSuccess {
            AppLog.i(TAG, "copied ${text.length} chars")
            // Android 13+ shows its own "Copied" overlay; only older builds need a toast.
            if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "복사됨", Toast.LENGTH_SHORT).show()
        }.onFailure { AppLog.e(TAG, "clipboard write failed", it) }
    }
}
