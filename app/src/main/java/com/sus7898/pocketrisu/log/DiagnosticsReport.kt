package com.sus7898.pocketrisu.log

import android.content.Context
import android.os.Build
import androidx.webkit.WebViewCompat
import com.sus7898.pocketrisu.BuildConfig
import com.sus7898.pocketrisu.settings.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the exported diagnostics text (docs/LOGGING.md §5). */
object DiagnosticsReport {

    fun header(context: Context, serverVersionLine: String?): String {
        val settings = AppSettings(context)
        val wv = WebViewCompat.getCurrentWebViewPackage(context)
        val host = settings.serverHost()
        val serverShown = if (settings.includeHostInExport) (host ?: "-") else "<server>"
        return buildString {
            appendLine("# PocketRisu App diagnostics")
            appendLine("app: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("webview: ${wv?.packageName ?: "?"} ${wv?.versionName ?: "?"}")
            appendLine("server: $serverShown ${serverVersionLine ?: "(version unknown)"} mode=server-ui")
            appendLine("verified-server-versions: ${BuildConfig.VERIFIED_SERVER_VERSIONS}")
            appendLine("settings: includeHost=${settings.includeHostInExport}")
            appendLine("exported: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())}")
            appendLine("----")
        }
    }

    fun build(context: Context, serverVersionLine: String?): String {
        val settings = AppSettings(context)
        val body = AppLog.readFiles().ifEmpty { AppLog.snapshot().joinToString("\n") }
        val text = header(context, serverVersionLine) + body
        return if (settings.includeHostInExport) text else Redactor.maskHost(text, settings.serverHost())
    }
}
