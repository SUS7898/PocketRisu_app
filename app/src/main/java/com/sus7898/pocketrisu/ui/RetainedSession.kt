package com.sus7898.pocketrisu.ui

import android.webkit.WebView
import com.sus7898.pocketrisu.bundle.BundleServer

/**
 * The live WebView and its session state, kept across Activity recreation (docs/TROUBLESHOOTING.md T-15).
 * A recreated MainActivity re-attaches this instead of loading the page again, so a keyboard
 * attach, DeX switch, font-size change or any other configuration change no longer costs a
 * full PocketRisu reload. Cleared only when the activity really finishes.
 */
object RetainedSession {
    var webView: WebView? = null
    var bundleServer: BundleServer? = null
    var bundleVersionInUse: String? = null
    var bundleVerified: Boolean = true
    var apiSeen: Boolean = false
    var serverBase: String? = null

    val isActive: Boolean get() = webView != null

    fun clear() {
        webView = null
        bundleServer = null
        bundleVersionInUse = null
        bundleVerified = true
        apiSeen = false
        serverBase = null
    }
}
