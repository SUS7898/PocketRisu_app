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

    /** Last successful probe, e.g. "version 1.14.0 (deploymentType=docker)"; shown when a fresh probe is still pending. */
    var lastServerInfo: String?
        get() = prefs.getString(KEY_LAST_SERVER_INFO, null)
        set(value) = prefs.edit { putString(KEY_LAST_SERVER_INFO, value) }

    /** false = stable releases only (default); true = follow the rolling nightly pre-release. */
    var nightlyChannel: Boolean
        get() = prefs.getBoolean(KEY_NIGHTLY, false)
        set(value) = prefs.edit { putBoolean(KEY_NIGHTLY, value) }

    var lastUpdateCheckMs: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_UPDATE_CHECK, value) }

    fun serverHttpUrl(): HttpUrl? = serverUrl?.toHttpUrlOrNull()

    fun serverHost(): String? = serverHttpUrl()?.host

    companion object {
        private const val KEY_SERVER_URL = "serverUrl"
        private const val KEY_INCLUDE_HOST = "includeHostInExport"
        private const val KEY_LAST_SERVER_INFO = "lastServerInfo"
        private const val KEY_NIGHTLY = "nightlyChannel"
        private const val KEY_LAST_UPDATE_CHECK = "lastUpdateCheckMs"

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
