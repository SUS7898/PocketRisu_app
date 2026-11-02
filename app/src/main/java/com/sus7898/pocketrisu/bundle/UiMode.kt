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
            allowHotfixMismatch: Boolean = false,
        ): UiMode = when {
            !enabled -> ServerUi("disabled in settings")
            bundleVersion.isNullOrEmpty() -> ServerUi("no bundle in this build")
            disabledForVersion == bundleVersion -> ServerUi("bundle v$bundleVersion disabled after repeated failures")
            serverVersion == null -> ServerUi("server version unknown")
            serverVersion != bundleVersion && !(allowHotfixMismatch && sameMinor(serverVersion, bundleVersion)) ->
                ServerUi("version mismatch: server $serverVersion, bundle $bundleVersion")
            patchSync == null -> ServerUi("server index.html unreadable (__PATCH_SYNC__)")
            else -> Bundled(bundleVersion, patchSync)
        }

        /**
         * `1.16.0` vs `1.16.2` → true; `1.16.x` vs `1.17.0` → false. Upstream ships hotfixes
         * (x.y.Z) several times a month and they have not touched the API contract so far
         * (docs/UPSTREAM-UPDATE.md §5), so a bundle one patch level behind keeps working. Minor
         * bumps still require a matching bundle. The repeated-failure fallback stays as the net.
         */
        fun sameMinor(a: String, b: String): Boolean {
            val pa = a.trim().removePrefix("v").split('.')
            val pb = b.trim().removePrefix("v").split('.')
            return pa.size >= 2 && pb.size >= 2 && pa[0] == pb[0] && pa[1] == pb[1]
        }
    }
}

/** Process-wide current mode for the diagnostics header and settings screen. */
object UiModeState {
    @Volatile
    var current: UiMode = UiMode.ServerUi("not decided yet")
}
