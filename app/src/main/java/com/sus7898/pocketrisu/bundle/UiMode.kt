package com.sus7898.pocketrisu.bundle

/** Which UI the WebView is running and why (docs/DESIGN.md §4.1). */
sealed class UiMode {
    data class Bundled(val upstreamVersion: String, val patchSync: Boolean) : UiMode() {
        override fun describe() = "bundled v$upstreamVersion patchSync=$patchSync"
    }

    data class ServerUi(val reason: String) : UiMode() {
        override fun describe() = "server-ui ($reason)"
    }

    abstract fun describe(): String

    companion object {
        /** Pure decision: the inputs are gathered by the caller so this stays unit-testable. */
        fun decide(
            enabled: Boolean,
            bundleVersion: String?,
            serverVersion: String?,
            disabledForVersion: String?,
            patchSync: Boolean?,
        ): UiMode = when {
            !enabled -> ServerUi("disabled in settings")
            bundleVersion.isNullOrEmpty() -> ServerUi("no bundle in this build")
            disabledForVersion == bundleVersion -> ServerUi("bundle v$bundleVersion disabled after repeated failures")
            serverVersion == null -> ServerUi("server version unknown")
            serverVersion != bundleVersion -> ServerUi("version mismatch: server $serverVersion, bundle $bundleVersion")
            patchSync == null -> ServerUi("server index.html unreadable (__PATCH_SYNC__)")
            else -> Bundled(bundleVersion, patchSync)
        }
    }
}

/** Process-wide current mode for the diagnostics header and settings screen. */
object UiModeState {
    @Volatile
    var current: UiMode = UiMode.ServerUi("not decided yet")
}
