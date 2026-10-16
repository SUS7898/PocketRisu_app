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

    /** Bundled UI mode (docs/DESIGN.md §4). Default on; the version gate decides per launch. */
    var bundleEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUNDLE_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_BUNDLE_ENABLED, value) }

    /** Bundle version switched off after two consecutive runtime failures; cleared when a bundle works. */
    var bundleDisabledForVersion: String?
        get() = prefs.getString(KEY_BUNDLE_DISABLED_FOR, null)
        set(value) = prefs.edit { putString(KEY_BUNDLE_DISABLED_FOR, value) }

    fun bundleFailures(version: String): Int = prefs.getInt("$KEY_BUNDLE_FAILS.$version", 0)

    /** Returns the new consecutive failure count. */
    fun recordBundleFailure(version: String): Int {
        val n = bundleFailures(version) + 1
        prefs.edit { putInt("$KEY_BUNDLE_FAILS.$version", n) }
        if (n >= BUNDLE_MAX_FAILURES) bundleDisabledForVersion = version
        return n
    }

    fun recordBundleHealthy(version: String) {
        prefs.edit { putInt("$KEY_BUNDLE_FAILS.$version", 0) }
        if (bundleDisabledForVersion == version) bundleDisabledForVersion = null
    }

    fun serverHttpUrl(): HttpUrl? = serverUrl?.toHttpUrlOrNull()

    fun serverHost(): String? = serverHttpUrl()?.host

    companion object {
        private const val KEY_SERVER_URL = "serverUrl"
        private const val KEY_INCLUDE_HOST = "includeHostInExport"
        private const val KEY_LAST_SERVER_INFO = "lastServerInfo"
        private const val KEY_NIGHTLY = "nightlyChannel"
        private const val KEY_LAST_UPDATE_CHECK = "lastUpdateCheckMs"
        private const val KEY_BUNDLE_ENABLED = "bundleEnabled"
        private const val KEY_BUNDLE_DISABLED_FOR = "bundleDisabledForVersion"
        private const val KEY_BUNDLE_FAILS = "bundleFailures"
        const val BUNDLE_MAX_FAILURES = 2

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
