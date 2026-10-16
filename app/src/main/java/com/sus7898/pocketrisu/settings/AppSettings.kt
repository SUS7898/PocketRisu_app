package com.sus7898.pocketrisu.settings

import android.content.Context
import androidx.core.content.edit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Non-secret app settings. Secrets (future auto-login password) go to an encrypted store, never here. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Normalised base URL without trailing slash, e.g. https://nas.example.com */
    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit { putString(KEY_SERVER_URL, value) }

    var includeHostInExport: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_HOST, false)
        set(value) = prefs.edit { putBoolean(KEY_INCLUDE_HOST, value) }

    fun serverHttpUrl(): HttpUrl? = serverUrl?.toHttpUrlOrNull()

    fun serverHost(): String? = serverHttpUrl()?.host

    companion object {
        private const val KEY_SERVER_URL = "serverUrl"
        private const val KEY_INCLUDE_HOST = "includeHostInExport"

        /** Validates user input; returns the normalised base URL or null. */
        fun normalizeServerUrl(raw: String, allowHttp: Boolean): String? {
            val trimmed = raw.trim().trimEnd('/')
            val url = trimmed.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" && !(allowHttp && url.scheme == "http")) return null
            if (url.host.isBlank()) return null
            return url.newBuilder().encodedPath("/").query(null).fragment(null).build()
                .toString().trimEnd('/')
        }
    }
}
