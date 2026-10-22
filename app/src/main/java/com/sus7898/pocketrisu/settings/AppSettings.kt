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

    /** Server version from the last successful probe, with its time; lets a launch start bundled while offline (T-9). */
    val lastServerVersion: String? get() = prefs.getString(KEY_LAST_SERVER_VERSION, null)
    val lastServerVersionAt: Long get() = prefs.getLong(KEY_LAST_SERVER_VERSION_AT, 0L)

    fun rememberServer(version: String, deploymentType: String) {
        lastServerInfo = "version $version (deploymentType=$deploymentType)"
        prefs.edit {
            putString(KEY_LAST_SERVER_VERSION, version)
            putLong(KEY_LAST_SERVER_VERSION_AT, System.currentTimeMillis())
        }
    }

    /** __PATCH_SYNC__ as last read from the server's index.html; a server setting, so stable across launches. */
    var lastPatchSync: Boolean?
        get() = if (prefs.contains(KEY_LAST_PATCH_SYNC)) prefs.getBoolean(KEY_LAST_PATCH_SYNC, false) else null
        set(value) = prefs.edit { if (value == null) remove(KEY_LAST_PATCH_SYNC) else putBoolean(KEY_LAST_PATCH_SYNC, value) }

    /** false = stable releases only (default); true = follow the rolling nightly pre-release. */
    var nightlyChannel: Boolean
        get() = prefs.getBoolean(KEY_NIGHTLY, false)
        set(value) = prefs.edit { putBoolean(KEY_NIGHTLY, value) }

    var lastUpdateCheckMs: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_UPDATE_CHECK, value) }

    /** Keep-alive foreground service while the app is open (T-12). Default on. */
    var keepAlive: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ALIVE, true)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_ALIVE, value) }

    /** With a hardware keyboard attached (e.g. a Bluetooth keyboard+touchpad), keep the on-screen
     *  keyboard from covering half the chat each time an input gets focus. Default on. */
    var hideImeWithHardwareKeyboard: Boolean
        get() = prefs.getBoolean(KEY_HIDE_IME_HW_KEYBOARD, true)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_IME_HW_KEYBOARD, value) }

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
        private const val KEY_LAST_SERVER_VERSION = "lastServerVersion"
        private const val KEY_LAST_SERVER_VERSION_AT = "lastServerVersionAt"
        private const val KEY_LAST_PATCH_SYNC = "lastPatchSync"
        /** A cached server version older than this is not trusted for the bundle gate. */
        const val CACHED_SERVER_VERSION_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val KEY_NIGHTLY = "nightlyChannel"
        private const val KEY_LAST_UPDATE_CHECK = "lastUpdateCheckMs"
        private const val KEY_KEEP_ALIVE = "keepAlive"
        private const val KEY_BUNDLE_ENABLED = "bundleEnabled"
        private const val KEY_HIDE_IME_HW_KEYBOARD = "hideImeWithHardwareKeyboard"
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
