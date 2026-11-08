package com.sus7898.pocketrisu.ui

import android.content.Context
import android.view.KeyEvent
import android.webkit.WebView
import com.sus7898.pocketrisu.log.AppLog

/**
 * WebView that reports what a hardware keyboard delivers on either side of the input method, for
 * the send-key investigation (docs/WORKLOG.md 11/08): a Rii i4+ Bluetooth keyboard on the Galaxy
 * types letters fine, but Ctrl+Enter never reaches the page's keydown handler.
 *
 * [dispatchKeyEventPreIme] sees the raw event before the IME; [dispatchKeyEvent] sees what the IME
 * let through or re-sent (an IME that re-dispatches with sendDownUpKeyEvents drops every modifier).
 * Only key-down of Enter keys and of Ctrl-modified keys is logged, so normal typing stays silent.
 */
class KeyLogWebView(context: Context) : WebView(context) {

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        log("preIme", event)
        return super.dispatchKeyEventPreIme(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        log("postIme", event)
        return super.dispatchKeyEvent(event)
    }

    private fun log(stage: String, e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN) return
        val enter = e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!enter && !e.isCtrlPressed) return
        AppLog.i(
            "Key",
            "$stage ${KeyEvent.keyCodeToString(e.keyCode)} ctrl=${e.isCtrlPressed} shift=${e.isShiftPressed}" +
                " alt=${e.isAltPressed} meta=${e.isMetaPressed} fn=${e.isFunctionPressed} repeat=${e.repeatCount}" +
                " metaState=0x${Integer.toHexString(e.metaState)} source=0x${Integer.toHexString(e.source)}" +
                " device=${e.device?.name ?: "?"}",
        )
    }
}
