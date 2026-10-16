package com.sus7898.pocketrisu.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sus7898.pocketrisu.BuildConfig
import com.sus7898.pocketrisu.R
import com.sus7898.pocketrisu.databinding.ActivitySetupBinding
import com.sus7898.pocketrisu.log.AppLog
import com.sus7898.pocketrisu.net.ServerProbe
import com.sus7898.pocketrisu.settings.AppSettings
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupBinding
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_PocketRisuApp_WithActionBar)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettings(this)

        binding.urlInput.setText(settings.serverUrl ?: "")
        binding.testButton.setOnClickListener { validate()?.let { test(it) } }
        binding.saveButton.setOnClickListener {
            val url = validate() ?: return@setOnClickListener
            settings.serverUrl = url
            AppLog.i("Setup", "server url saved host=${AppSettings(this).serverHost()}")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }

    private fun validate(): String? {
        val raw = binding.urlInput.text?.toString().orEmpty()
        val normalized = AppSettings.normalizeServerUrl(raw, allowHttp = BuildConfig.DEBUG)
        if (normalized == null) {
            val msg = if (raw.trim().startsWith("http://")) R.string.setup_err_https else R.string.setup_err_invalid
            binding.status.text = getString(msg)
            return null
        }
        return normalized
    }

    private fun test(url: String) {
        binding.status.text = getString(R.string.setup_testing)
        binding.testButton.isEnabled = false
        lifecycleScope.launch {
            val result = ServerProbe.probe(url)
            binding.testButton.isEnabled = true
            binding.status.text = result.fold(
                onSuccess = { getString(R.string.setup_ok, it.version, it.deploymentType) },
                onFailure = { getString(R.string.setup_fail, it.message ?: it.javaClass.simpleName) },
            )
        }
    }
}
