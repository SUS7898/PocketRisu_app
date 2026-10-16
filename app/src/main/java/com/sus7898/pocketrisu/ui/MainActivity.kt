package com.sus7898.pocketrisu.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.sus7898.pocketrisu.keepalive.KeepAliveService
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
 *
 * The WebView lives in [RetainedSession] and survives Activity recreation (T-15): the page is
 * loaded once per process, not once per configuration change.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    private var lastBackPressMs = 0L
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val session get() = RetainedSession
    private val handler = Handler(Looper.getMainLooper())
    private val healthCheck = Runnable { if (!session.apiSeen) fallbackToServerUi("no /api traffic within ${HEALTH_TIMEOUT_MS / 1000}s") }

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        AppLog.i(TAG, "notification permission granted=$granted")
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
        applySystemInsets()

        binding.menuHandle.setOnClickListener {
            AppMenuSheet.show(this) { session.webView?.reload() }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = session.webView
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

        startKeepAliveIfEnabled()

        val retained = session.webView
        if (retained != null && session.serverBase == base) {
            // Activity was recreated (configuration change); the page is still alive.
            attachWebView(retained)
            AppLog.i(TAG, "WebView re-attached after activity recreation (mode=${UiModeState.current.describe()})")
            if (session.bundleVersionInUse != null && !session.apiSeen) handler.postDelayed(healthCheck, HEALTH_TIMEOUT_MS)
            return
        }
        if (retained != null) {
            // Server changed while a session was alive: start over.
            destroyRetained()
        }

        lifecycleScope.launch {
            val mode = decideMode(base)
            UiModeState.current = mode
            AppLog.i(TAG, "mode=${mode.describe()}")
            session.serverBase = base
            if (mode is UiMode.Bundled) {
                session.bundleServer = BundleServer(BundleRepository(this@MainActivity), Uri.parse(base).host ?: "", mode.patchSync)
                session.bundleVersionInUse = mode.upstreamVersion
            }
            createWebView(base)
        }
        checkForUpdateDaily()
    }

    /**
     * targetSdk 35 draws edge-to-edge, so without this the web UI's header sits under the status
     * bar and its bottom row under the gesture bar (docs/TROUBLESHOOTING.md T-11). PocketRisu's CSS
     * does not use safe-area insets, so the shell keeps the page out of the system bars, the camera
     * cutout and the keyboard (IME inset = the chat input stays visible while typing).
     */
    private fun applySystemInsets() {
        val root = binding.root
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    /**
     * Gathers the inputs of UiMode.decide. Probes are cancellable and capped (T-9), and when the
     * server cannot be reached the bundle starts on the last known server version, to be verified
     * as soon as the first API request proves the server is back ([verifyServerVersionLater]).
     */
    private suspend fun decideMode(base: String): UiMode {
        val repo = BundleRepository(this)
        if (!settings.bundleEnabled || !repo.available) {
            return UiMode.decide(settings.bundleEnabled, repo.version, null, settings.bundleDisabledForVersion, null)
        }
        val bundleVersion = repo.version
        if (settings.bundleDisabledForVersion == bundleVersion) {
            return UiMode.decide(true, bundleVersion, null, settings.bundleDisabledForVersion, null)
        }
        val (serverVersion, patchSync) = coroutineScope {
            val server = async { withTimeoutOrNull(PROBE_TIMEOUT_MS) { ServerProbe.probe(base, settings).getOrNull() } }
            val ps = async { withTimeoutOrNull(PROBE_TIMEOUT_MS) { ServerIndexProbe.fetchPatchSync(base) } }
            server.await()?.version to ps.await()
        }
        if (patchSync != null) settings.lastPatchSync = patchSync
        if (serverVersion != null) {
            session.bundleVerified = true
            return UiMode.decide(true, bundleVersion, serverVersion, settings.bundleDisabledForVersion, patchSync ?: settings.lastPatchSync)
        }
        val cached = settings.lastServerVersion
        val cachedFresh = cached != null && System.currentTimeMillis() - settings.lastServerVersionAt < AppSettings.CACHED_SERVER_VERSION_MAX_AGE_MS
        if (cachedFresh) {
            session.bundleVerified = false
            AppLog.w("Bundle", "server probe failed within ${PROBE_TIMEOUT_MS}ms; starting on cached server version $cached (unverified)")
            return UiMode.decide(true, bundleVersion, cached, settings.bundleDisabledForVersion, settings.lastPatchSync)
        }
        return UiMode.decide(true, bundleVersion, null, settings.bundleDisabledForVersion, null)
    }

    /** Bundled on a cached version: once the server answers, confirm it still matches the bundle. */
    private fun verifyServerVersionLater(base: String) {
        lifecycleScope.launch {
            val info = ServerProbe.probe(base, settings).getOrNull() ?: return@launch
            val bundle = session.bundleVersionInUse ?: return@launch
            if (info.version == bundle) {
                session.bundleVerified = true
                AppLog.i("Bundle", "cached server version confirmed (${info.version})")
            } else {
                fallbackToServerUi("server version changed to ${info.version} (bundle $bundle)", countFailure = false)
            }
        }
    }

    /** Bundled UI misbehaved (or no longer matches): maybe count it, and reload the server UI. */
    private fun fallbackToServerUi(reason: String, countFailure: Boolean = true) {
        val version = session.bundleVersionInUse ?: return
        handler.removeCallbacks(healthCheck)
        if (countFailure) {
            val failures = settings.recordBundleFailure(version)
            AppLog.e("Bundle", "fallback to server-ui: $reason (failure $failures/${AppSettings.BUNDLE_MAX_FAILURES} for v$version)")
        } else {
            AppLog.w("Bundle", "switching to server-ui: $reason")
        }
        session.bundleServer = null
        session.bundleVersionInUse = null
        UiModeState.current = UiMode.ServerUi("fallback: $reason")
        Toast.makeText(this, getString(R.string.bundle_fallback_toast, reason), Toast.LENGTH_LONG).show()
        val base = settings.serverUrl ?: return
        destroyRetained()
        createWebView(base)
    }

    private fun markApiSeen() {
        if (session.apiSeen) return
        session.apiSeen = true
        val version = session.bundleVersionInUse ?: return
        handler.removeCallbacks(healthCheck)
        settings.recordBundleHealthy(version)
        AppLog.i("Bundle", "healthy: first /api request observed (served ${session.bundleServer?.served} bundle files)")
        if (!session.bundleVerified) settings.serverUrl?.let { verifyServerVersionLater(it) }
    }

    /**
     * T-12: the renderer holding the decoded database was killed while the app was in the
     * background and the process itself was restarted; each time the whole bootstrap ran again.
     * A foreground service keeps the process (and with RENDERER_PRIORITY_IMPORTANT the renderer)
     * off the top of the kill list, and out of the cached-apps freezer (T-14).
     */
    private fun startKeepAliveIfEnabled() {
        if (!settings.keepAlive) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        KeepAliveService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed in SetupActivity; a new host means a fresh page.
        val base = settings.serverUrl ?: return
        val current = session.webView?.url
        if (current != null && !current.startsWith(base, ignoreCase = true)) {
            AppLog.i(TAG, "server url changed; reloading")
            session.serverBase = base
            session.webView?.loadUrl("$base/")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(healthCheck)
        if (isFinishing) {
            destroyRetained()
            KeepAliveService.stop(this)
        } else {
            // Configuration change: keep the live page, just let go of this activity.
            detachWebView()
        }
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

    // ── WebView lifecycle ──────────────────────────────────────────────────────

    private fun attachWebView(wv: WebView) {
        (wv.context as? MutableContextWrapper)?.baseContext = this
        (wv.webViewClient as? ShellWebViewClient)?.host = this
        (wv.webChromeClient as? ShellWebChromeClient)?.host = this
        (wv.parent as? ViewGroup)?.removeView(wv)
        binding.webViewContainer.addView(
            wv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun detachWebView() {
        val wv = session.webView ?: return
        binding.webViewContainer.removeView(wv)
        (wv.context as? MutableContextWrapper)?.baseContext = applicationContext
        (wv.webViewClient as? ShellWebViewClient)?.host = null
        (wv.webChromeClient as? ShellWebChromeClient)?.host = null
    }

    private fun destroyRetained() {
        session.webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        session.clear()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(base: String) {
        // MutableContextWrapper lets the same WebView be handed to the next Activity instance.
        val wv = WebView(MutableContextWrapper(this))
        session.webView = wv
        session.serverBase = base
        session.apiSeen = false
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

        wv.webViewClient = ShellWebViewClient(this, base)
        wv.webChromeClient = ShellWebChromeClient(this)
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            (wv.webViewClient as? ShellWebViewClient)?.host?.startDownload(url, userAgent, contentDisposition, mimeType)
        }

        val pkg = WebViewCompat.getCurrentWebViewPackage(this)
        AppLog.i(TAG, "WebView ready ${pkg?.packageName ?: "?"} ${pkg?.versionName ?: "?"}")

        if (session.bundleVersionInUse != null) {
            handler.removeCallbacks(healthCheck)
            handler.postDelayed(healthCheck, HEALTH_TIMEOUT_MS)
        }
        wv.loadUrl("$base/")
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

    private fun launchFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback
        return runCatching { fileChooser.launch(params.createIntent()); true }
            .getOrElse {
                AppLog.w(TAG, "file chooser unavailable", it)
                fileChooserCallback = null
                false
            }
    }

    private fun onRendererGone(view: WebView, detail: RenderProcessGoneDetail, base: String) {
        AppLog.e(TAG, "renderer gone crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}")
        val lastUrl = view.url
        val bundledBeforeBoot = session.bundleVersionInUse != null && !session.apiSeen
        destroyRetained()
        if (bundledBeforeBoot) {
            // Crashed before the bundled UI ever talked to the server: treat as a bundle failure.
            session.bundleVersionInUse = null
            fallbackToServerUi("renderer gone before boot")
            return
        }
        Toast.makeText(this, R.string.renderer_restarted, Toast.LENGTH_LONG).show()
        createWebView(base)
        if (lastUrl != null) session.webView?.loadUrl(lastUrl)
    }

    /** Clients hold the current host activity by a mutable reference so a retained WebView can be re-homed. */
    private class ShellWebViewClient(@Volatile var host: MainActivity?, private val base: String) : WebViewClient() {
        private val baseHost = Uri.parse(base).host

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host.equals(baseHost, ignoreCase = true)) return false
            // Anything off-server opens in the system browser; the shell stays on PocketRisu.
            AppLog.d(TAG, "external link -> browser host=${url.host}")
            val h = host ?: return true
            runCatching { h.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                .onFailure { AppLog.w(TAG, "no handler for external link", it) }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Bundled UI files come from the APK; everything else (all API routes) goes to the server.
            RetainedSession.bundleServer?.intercept(request)?.let { return it }
            val path = request.url.encodedPath
            if (path != null && path.startsWith("/api/") && request.url.host.equals(baseHost, ignoreCase = true)) {
                host?.let { h -> h.runOnUiThread { h.markApiSeen() } }
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
            val h = host ?: return
            if (RetainedSession.bundleVersionInUse != null) {
                h.fallbackToServerUi("main frame error ${error.errorCode}")
                return
            }
            Toast.makeText(h, h.getString(R.string.page_error, error.description), Toast.LENGTH_LONG).show()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val h = host
            if (h == null) {
                // Detached during a configuration change: drop the dead view; the next activity recreates.
                AppLog.e(TAG, "renderer gone while detached crashed=${detail.didCrash()}")
                RetainedSession.webView?.destroy()
                RetainedSession.clear()
                return true
            }
            h.onRendererGone(view, detail, base)
            return true
        }
    }

    private class ShellWebChromeClient(@Volatile var host: MainActivity?) : WebChromeClient() {
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
        ): Boolean = host?.launchFileChooser(filePathCallback, fileChooserParams) ?: false
    }

    companion object {
        private const val TAG = "Shell"
        private const val PROBE_TIMEOUT_MS = 6_000L
        private const val HEALTH_TIMEOUT_MS = 30_000L
    }
}
