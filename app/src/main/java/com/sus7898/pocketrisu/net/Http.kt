package com.sus7898.pocketrisu.net

import android.webkit.CookieManager
import com.sus7898.pocketrisu.BuildConfig
import com.sus7898.pocketrisu.log.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One OkHttp client for all native requests. Cookies are shared with the WebView so a native
 * request is the same session the web UI is logged into (risu-session is HttpOnly; we never read
 * its value, only hand the cookie string back to the server).
 */
object Http {
    private const val TAG = "Net"

    val userAgentSuffix: String = "PocketRisuApp/${BuildConfig.VERSION_NAME}"

    private val webViewCookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val header = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
            return header.split(';').mapNotNull { part ->
                Cookie.parse(url, part.trim())
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cm = CookieManager.getInstance()
            cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
            cm.flush()
        }
    }

    private val timing = Interceptor { chain ->
        val req = chain.request()
        val start = System.nanoTime()
        try {
            val res: Response = chain.proceed(req)
            val ms = (System.nanoTime() - start) / 1_000_000
            AppLog.d(TAG, "${req.method} ${req.url.encodedPath} -> ${res.code} ${ms}ms")
            res
        } catch (e: Exception) {
            val ms = (System.nanoTime() - start) / 1_000_000
            AppLog.w(TAG, "${req.method} ${req.url.encodedPath} failed after ${ms}ms", e)
            throw e
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(webViewCookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(timing)
            .build()
    }

    /**
     * For launch-time probes: short timeouts and no route retries, so an unreachable server costs
     * seconds, not minutes (docs/TROUBLESHOOTING.md T-9: a probe once blocked launch for 44s and a
     * connect attempt ran 271s through OkHttp's route retries).
     */
    val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** Suspends on the call and cancels it when the coroutine is cancelled (so withTimeout works). */
    suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }
}
