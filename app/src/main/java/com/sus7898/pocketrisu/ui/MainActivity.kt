package com.sus7898.pocketrisu.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import com.sus7898.pocketrisu.R
import com.sus7898.pocketrisu.bundle.BundleRepository
import com.sus7898.pocketrisu.bundle.BundleServer
import com.sus7898.pocketrisu.bundle.ServerIndexProbe
import com.sus7898.pocketrisu.bundle.UiMode
import com.sus7898.pocketrisu.bundle.UiModeState
import com.sus7898.pocketrisu.databinding.ActivityMainBinding
import com.sus7898.pocketrisu.intercept.RequestObserver
import com.sus7898.pocketrisu.log.AppLog
import com.sus7898.pocketrisu.net.Http
import com.sus7898.pocketrisu.net.ServerProbe
import com.sus7898.pocketrisu.settings.AppSettings
import com.sus7898.pocketrisu.update.UpdateChecker
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WebView shell (docs/DESIGN.md §3.1) with the bundled-UI version gate and runtime fallback (§4.1).
 * Launch: decide the mode (bundle enabled? bundle present? server version == bundle version?
 * __PATCH_SYNC__ readable?) → load. In bundled mode the interceptor serves index.html and the
 * hashed assets from the APK; every API request still goes to the server.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    private var webView: WebView? = null
    private var lastBackPressMs = 0L
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private var bundleServer: BundleServer? = null
    private var bundleVersionInUse: String? = null
    private var apiSeen = false
    private val handler = Handler(Looper.getMainLooper())
    private val healthCheck = Runnable { if (!apiSeen) fallbackToServerUi("no /api traffic within ${HEALTH_TIMEOUT_MS / 1000}s") }

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        val base = settings.serverUrl
        if (base == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.menuHandle.setOnClickListener {
            AppMenuSheet.show(this) { webView?.reload() }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressMs < 2_000) {
                    finish()
                } else {
                    lastBackPressMs = now
                    Toast.makeText(this@MainActivity, R.string.back_again_to_exit, Toast.LENGTH_SHORT).show()
                }
            }
        })

        lifecycleScope.launch {
            val mode = decideMode(base)
            UiModeState.current = mode
            AppLog.i(TAG, "mode=${mode.describe()}")
            if (mode is UiMode.Bundled) {
                bundleServer = BundleServer(BundleRepository(this@MainActivity), Uri.parse(base).host ?: "", mode.patchSync)
                bundleVersionInUse = mode.upstreamVersion
            }
            createWebView(base, savedInstanceState)
        }
        checkForUpdateDaily()
    }

    /** Gathers the inputs of UiMode.decide; each probe is capped so launch never hangs on the network. */
    private suspend fun decideMode(base: String): UiMode {
        val repo = BundleRepository(this)
        if (!settings.bundleEnabled || !repo.available) {
            return UiMode.decide(settings.bundleEnabled, repo.version, null, settings.bundleDisabledForVersion, null)
        }
        val bundleVersion = repo.version
        if (settings.bundleDisabledForVersion == bundleVersion) {
            return UiMode.decide(true, bundleVersion, null, settings.bundleDisabledForVersion, null)
        }
        return coroutineScope {
            val server = async { withTimeoutOrNull(PROBE_TIMEOUT_MS) { ServerProbe.probe(base).getOrNull() } }
            val patchSync = async { withTimeoutOrNull(PROBE_TIMEOUT_MS) { ServerIndexProbe.fetchPatchSync(base) } }
            val serverVersion = server.await()?.version
            if (serverVersion != null) settings.lastServerInfo = "version $serverVersion (deploymentType=${server.await()?.deploymentType})"
            UiMode.decide(true, bundleVersion, serverVersion, settings.bundleDisabledForVersion, patchSync.await())
        }
    }

    /** Bundled UI misbehaved: count it, maybe disable the bundle version, and reload the server UI. */
    private fun fallbackToServerUi(reason: String) {
        val version = bundleVersionInUse ?: return
        handler.removeCallbacks(healthCheck)
        val failures = settings.recordBundleFailure(version)
        AppLog.e("Bundle", "fallback to server-ui: $reason (failure $failures/${AppSettings.BUNDLE_MAX_FAILURES} for v$version)")
        bundleServer = null
        bundleVersionInUse = null
        UiModeState.current = UiMode.ServerUi("fallback: $reason")
        Toast.makeText(this, getString(R.string.bundle_fallback_toast, reason), Toast.LENGTH_LONG).show()
        val base = settings.serverUrl ?: return
        webView?.let { old ->
            binding.webViewContainer.removeView(old)
            old.destroy()
        }
        webView = null
        createWebView(base, null)
    }

    private fun markApiSeen() {
        if (apiSeen) return
        apiSeen = true
        val version = bundleVersionInUse ?: return
        handler.removeCallbacks(healthCheck)
        settings.recordBundleHealthy(version)
        AppLog.i("Bundle", "healthy: first /api request observed (served ${bundleServer?.served} bundle files)")
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed in SetupActivity; a new host means a fresh page.
        val base = settings.serverUrl ?: return
        val current = webView?.url
        if (current != null && !current.startsWith(base, ignoreCase = true)) {
            AppLog.i(TAG, "server url changed; reloading")
            webView?.loadUrl("$base/")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(healthCheck)
        webView?.let {
            binding.webViewContainer.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    /** At most once a day: tell the user an update exists; installing stays a manual step in Settings. */
    private fun checkForUpdateDaily() {
        val now = System.currentTimeMillis()
        if (now - settings.lastUpdateCheckMs < 24 * 60 * 60 * 1000L) return
        lifecycleScope.launch {
            val channel = if (settings.nightlyChannel) UpdateChecker.Channel.NIGHTLY else UpdateChecker.Channel.STABLE
            UpdateChecker.fetchLatest(channel).onSuccess { rel ->
                settings.lastUpdateCheckMs = now
                if (rel.isNewer) {
                    Toast.makeText(this@MainActivity, getString(R.string.update_available_toast, rel.versionName), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(base: String, savedState: Bundle?) {
        val wv = WebView(this)
        webView = wv
        binding.webViewContainer.addView(
            wv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString ${Http.userAgentSuffix}"
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)
        // Keep the renderer alive under memory pressure; the chat page is expensive to rebuild.
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        wv.webViewClient = ShellWebViewClient(base)
        wv.webChromeClient = ShellWebChromeClient()
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

        val pkg = WebViewCompat.getCurrentWebViewPackage(this)
        AppLog.i(TAG, "WebView ready ${pkg?.packageName ?: "?"} ${pkg?.versionName ?: "?"}")

        apiSeen = false
        if (bundleVersionInUse != null) {
            handler.removeCallbacks(healthCheck)
            handler.postDelayed(healthCheck, HEALTH_TIMEOUT_MS)
        }
        if (savedState != null && wv.restoreState(savedState) != null) {
            AppLog.d(TAG, "state restored")
        } else {
            wv.loadUrl("$base/")
        }
    }

    private fun startDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        if (!url.startsWith("http")) {
            // blob:/data: downloads (exports produced in-page) need a JS bridge; milestone 7.
            AppLog.w(TAG, "download scheme not supported yet: ${url.substringBefore(':')}")
            Toast.makeText(this, "이 형식의 다운로드는 아직 지원하지 않습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        AppLog.i(TAG, "download started name=$fileName mime=$mimeType")
    }

    private inner class ShellWebViewClient(private val base: String) : WebViewClient() {
        private val baseHost = Uri.parse(base).host

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host.equals(baseHost, ignoreCase = true)) return false
            // Anything off-server opens in the system browser; the shell stays on PocketRisu.
            AppLog.d(TAG, "external link -> browser host=${url.host}")
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                .onFailure { AppLog.w(TAG, "no handler for external link", it) }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Bundled UI files come from the APK; everything else (all /api/*) goes to the server.
            bundleServer?.intercept(request)?.let { return it }
            val path = request.url.encodedPath
            if (path != null && path.startsWith("/api/") && request.url.host.equals(baseHost, ignoreCase = true)) {
                runOnUiThread { markApiSeen() }
            }
            // Milestone 1 observation; the tee/measure layer replaces this (DESIGN.md §3.2).
            RequestObserver.observe(request)
            return null
        }

        override fun onPageFinished(view: WebView, url: String) {
            AppLog.i(TAG, "page finished path=${Uri.parse(url).path}")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            AppLog.e(TAG, "main frame error code=${error.errorCode} desc=${error.description}")
            if (bundleVersionInUse != null) {
                fallbackToServerUi("main frame error ${error.errorCode}")
                return
            }
            Toast.makeText(this@MainActivity, getString(R.string.page_error, error.description), Toast.LENGTH_LONG).show()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            AppLog.e(TAG, "renderer gone crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}")
            val lastUrl = view.url
            binding.webViewContainer.removeView(view)
            view.destroy()
            webView = null
            if (bundleVersionInUse != null && !apiSeen) {
                // Crashed before the bundled UI ever talked to the server: treat as a bundle failure.
                fallbackToServerUi("renderer gone before boot")
                return true
            }
            Toast.makeText(this@MainActivity, R.string.renderer_restarted, Toast.LENGTH_LONG).show()
            createWebView(base, null)
            if (lastUrl != null) webView?.loadUrl(lastUrl)
            return true
        }
    }

    private inner class ShellWebChromeClient : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            // Errors always; warnings only when PocketRisu tagged them ("[Save] …", "[Storage] …"),
            // which is where patch rejections and full-write fallbacks are reported (T-7).
            // Truncated: console output can echo page data.
            val level = consoleMessage.messageLevel()
            val msg = consoleMessage.message()
            val keep = level == ConsoleMessage.MessageLevel.ERROR ||
                (level == ConsoleMessage.MessageLevel.WARNING && msg.startsWith("["))
            if (keep) {
                AppLog.w("Shell", "console[${level.name.lowercase()}]: ${msg.take(300)} (${consoleMessage.sourceId().substringAfterLast('/')}:${consoleMessage.lineNumber()})")
            }
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback
            return runCatching { fileChooser.launch(fileChooserParams.createIntent()); true }
                .getOrElse {
                    AppLog.w(TAG, "file chooser unavailable", it)
                    fileChooserCallback = null
                    false
                }
        }
    }

    companion object {
        private const val TAG = "Shell"
        private const val PROBE_TIMEOUT_MS = 6_000L
        private const val HEALTH_TIMEOUT_MS = 30_000L
    }
}
