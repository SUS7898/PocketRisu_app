package com.sus7898.pocketrisu.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import androidx.webkit.WebViewCompat
import com.sus7898.pocketrisu.R
import com.sus7898.pocketrisu.databinding.ActivityMainBinding
import com.sus7898.pocketrisu.log.AppLog
import com.sus7898.pocketrisu.net.Http
import com.sus7898.pocketrisu.settings.AppSettings

/**
 * WebView shell (docs/DESIGN.md §3.1). Milestone 1: the server UI is loaded as-is; the
 * interception hook only observes the API routes that later milestones will cache.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    private var webView: WebView? = null
    private var lastBackPressMs = 0L
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

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

        createWebView(base, savedInstanceState)
        AppLog.i(TAG, "mode=server-ui")
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
        webView?.let {
            binding.webViewContainer.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
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
            // Milestone 1: observe only. Milestone 2 adds the tee/measure layer here (DESIGN.md §3.2).
            // Bootstrap (/api/read) is logged at I so the exported log at least shows when the
            // data load started relative to "page finished"; the rest stays at D.
            val path = request.url.encodedPath ?: return null
            if (request.method == "GET" && OBSERVED_PREFIXES.any { path.startsWith(it) }) {
                if (path.startsWith("/api/read")) AppLog.i("Intercept", "observe GET /api/read (bootstrap)")
                else AppLog.d("Intercept", "observe GET ${path.take(40)}")
            }
            return null
        }

        override fun onPageFinished(view: WebView, url: String) {
            AppLog.i(TAG, "page finished path=${Uri.parse(url).path}")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            AppLog.e(TAG, "main frame error code=${error.errorCode} desc=${error.description}")
            Toast.makeText(this@MainActivity, getString(R.string.page_error, error.description), Toast.LENGTH_LONG).show()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            AppLog.e(TAG, "renderer gone crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}")
            val lastUrl = view.url
            binding.webViewContainer.removeView(view)
            view.destroy()
            webView = null
            Toast.makeText(this@MainActivity, R.string.renderer_restarted, Toast.LENGTH_LONG).show()
            createWebView(base, null)
            if (lastUrl != null) webView?.loadUrl(lastUrl)
            return true
        }
    }

    private inner class ShellWebChromeClient : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            // Only errors, truncated: console output can echo page data.
            if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                AppLog.w("Shell", "console: ${consoleMessage.message().take(300)} (${consoleMessage.sourceId().substringAfterLast('/')}:${consoleMessage.lineNumber()})")
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
        private val OBSERVED_PREFIXES = listOf("/api/read", "/api/chat-content/", "/api/asset/", "/api/model-jobs")
    }
}
