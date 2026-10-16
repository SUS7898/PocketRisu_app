package com.sus7898.pocketrisu.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.sus7898.pocketrisu.BuildConfig
import com.sus7898.pocketrisu.R
import com.sus7898.pocketrisu.databinding.ActivityDiagnosticsBinding
import com.sus7898.pocketrisu.log.AppLog
import com.sus7898.pocketrisu.log.DiagnosticsReport
import com.sus7898.pocketrisu.net.ServerProbe
import com.sus7898.pocketrisu.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDiagnosticsBinding
    private lateinit var settings: AppSettings
    private var serverVersionLine: String? = null

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) writeReport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_PocketRisuApp_WithActionBar)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettings(this)

        binding.includeHostSwitch.isChecked = settings.includeHostInExport
        binding.includeHostSwitch.setOnCheckedChangeListener { _, checked ->
            settings.includeHostInExport = checked
            renderHeader()
        }
        binding.refreshButton.setOnClickListener { renderLog() }
        binding.clearButton.setOnClickListener {
            AppLog.clear()
            AppLog.i("Diag", "log cleared by user")
            Toast.makeText(this, R.string.diag_cleared, Toast.LENGTH_SHORT).show()
            renderLog()
        }
        binding.exportButton.setOnClickListener { createDocument.launch(suggestedFileName()) }
        binding.shareButton.setOnClickListener { shareReport() }

        renderHeader()
        renderLog()
        probeServer()
    }

    private fun suggestedFileName(): String =
        "pocketrisu-app-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"

    private fun probeServer() {
        val base = settings.serverUrl ?: return
        // Until the probe answers, show (and export) the last known answer rather than "unknown"
        // (docs/TROUBLESHOOTING.md T-4).
        serverVersionLine = settings.lastServerInfo?.let { "$it [cached]" }
        lifecycleScope.launch {
            serverVersionLine = ServerProbe.probe(base, settings).fold(
                onSuccess = { "version ${it.version} (deploymentType=${it.deploymentType})" },
                onFailure = { "unreachable: ${it.message ?: it.javaClass.simpleName}" },
            )
            renderHeader()
        }
    }

    private fun renderHeader() {
        binding.header.text = DiagnosticsReport.header(this, serverVersionLine ?: getString(R.string.diag_header_loading))
    }

    private fun renderLog() {
        binding.logText.text = AppLog.snapshot().joinToString("\n")
        binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun writeReport(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)!!.bufferedWriter().use {
                        it.write(DiagnosticsReport.build(this@DiagnosticsActivity, serverVersionLine))
                    }
                }
            }
            result.fold(
                onSuccess = {
                    AppLog.i("Diag", "log exported to document")
                    Toast.makeText(this@DiagnosticsActivity, R.string.diag_exported, Toast.LENGTH_SHORT).show()
                },
                onFailure = {
                    AppLog.e("Diag", "log export failed", it)
                    Toast.makeText(this@DiagnosticsActivity, getString(R.string.diag_export_failed, it.message), Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun shareReport() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "share").apply { mkdirs() }
                File(dir, suggestedFileName()).apply {
                    writeText(DiagnosticsReport.build(this@DiagnosticsActivity, serverVersionLine))
                }
            }
            val uri = FileProvider.getUriForFile(this@DiagnosticsActivity, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            AppLog.i("Diag", "log shared via intent")
            startActivity(Intent.createChooser(send, getString(R.string.diag_share)))
        }
    }
}
