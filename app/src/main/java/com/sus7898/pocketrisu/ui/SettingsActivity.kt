package com.sus7898.pocketrisu.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sus7898.pocketrisu.BuildConfig
import com.sus7898.pocketrisu.R
import com.sus7898.pocketrisu.bundle.BundleRepository
import com.sus7898.pocketrisu.bundle.UiModeState
import com.sus7898.pocketrisu.databinding.ActivitySettingsBinding
import com.sus7898.pocketrisu.log.AppLog
import com.sus7898.pocketrisu.settings.AppSettings
import com.sus7898.pocketrisu.update.UpdateChecker
import com.sus7898.pocketrisu.update.UpdateInstaller
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings
    private lateinit var installer: UpdateInstaller
    private var found: UpdateChecker.Release? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_PocketRisuApp_WithActionBar)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettings(this)
        installer = UpdateInstaller(this)

        binding.appInfo.text = getString(
            R.string.settings_app_info,
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE, BuildConfig.VERIFIED_SERVER_VERSIONS,
        )
        binding.serverButton.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        binding.diagnosticsButton.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }

        binding.nightlySwitch.isChecked = settings.nightlyChannel
        binding.nightlySwitch.setOnCheckedChangeListener { _, checked ->
            settings.nightlyChannel = checked
            AppLog.i("Update", "channel=${if (checked) "nightly" else "stable"}")
            found = null
            binding.installButton.visibility = View.GONE
            binding.updateStatus.text = ""
        }
        binding.bundleSwitch.isChecked = settings.bundleEnabled
        binding.bundleSwitch.setOnCheckedChangeListener { _, checked ->
            settings.bundleEnabled = checked
            AppLog.i("Bundle", "setting bundleEnabled=$checked")
            renderBundle()
        }
        binding.checkButton.setOnClickListener { check() }
        binding.installButton.setOnClickListener { found?.let { downloadAndInstall(it) } }
        binding.releaseLink.setOnClickListener {
            val url = found?.htmlUrl?.takeIf { it.isNotBlank() }
                ?: "https://github.com/${BuildConfig.UPDATE_REPO_OWNER}/${BuildConfig.UPDATE_REPO_NAME}/releases"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        renderServer()
    }

    override fun onResume() {
        super.onResume()
        renderServer()
    }

    private fun renderServer() {
        binding.serverValue.text = settings.serverHost() ?: getString(R.string.settings_server_unset)
        renderBundle()
    }

    private fun renderBundle() {
        val repo = BundleRepository(this)
        val bundle = if (repo.available) "v${repo.version} (${repo.manifest?.fileCount} files)" else getString(R.string.settings_bundle_none)
        binding.bundleStatus.text = getString(R.string.settings_bundle_status, bundle, UiModeState.current.describe())
    }

    private fun channel() = if (settings.nightlyChannel) UpdateChecker.Channel.NIGHTLY else UpdateChecker.Channel.STABLE

    private fun check() {
        binding.checkButton.isEnabled = false
        binding.installButton.visibility = View.GONE
        binding.updateStatus.text = getString(R.string.settings_checking)
        lifecycleScope.launch {
            val result = UpdateChecker.fetchLatest(channel())
            binding.checkButton.isEnabled = true
            result.fold(
                onSuccess = { rel ->
                    settings.lastUpdateCheckMs = System.currentTimeMillis()
                    if (rel.isNewer) {
                        found = rel
                        binding.updateStatus.text = getString(R.string.settings_update_available, rel.versionName, rel.apkSize / 1024 / 1024.0)
                        binding.releaseNotes.text = rel.notes.take(2000)
                        binding.installButton.visibility = View.VISIBLE
                    } else {
                        binding.updateStatus.text = getString(R.string.settings_up_to_date, rel.versionName)
                        binding.releaseNotes.text = ""
                    }
                },
                onFailure = { binding.updateStatus.text = getString(R.string.settings_check_failed, it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    private fun downloadAndInstall(rel: UpdateChecker.Release) {
        if (!installer.canInstall()) {
            AppLog.w("Update", "install from unknown sources not yet allowed; opening system setting")
            Toast.makeText(this, R.string.settings_allow_unknown, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        binding.installButton.isEnabled = false
        binding.progress.visibility = View.VISIBLE
        binding.progress.isIndeterminate = false
        binding.progress.progress = 0
        lifecycleScope.launch {
            runCatching {
                val file = installer.download(rel) { done, total ->
                    runOnUiThread {
                        if (total > 0) binding.progress.progress = (done * 100 / total).toInt()
                        binding.updateStatus.text = getString(R.string.settings_downloading, done / 1024 / 1024.0, total / 1024 / 1024.0)
                    }
                }
                binding.updateStatus.text = getString(R.string.settings_verifying)
                binding.progress.isIndeterminate = true
                installer.verify(file, rel)
                binding.updateStatus.text = getString(R.string.settings_installing)
                installer.install(file)
            }.onFailure {
                binding.updateStatus.text = getString(R.string.settings_install_failed, it.message ?: it.javaClass.simpleName)
                binding.installButton.isEnabled = true
            }
            binding.progress.visibility = View.GONE
        }
    }
}
