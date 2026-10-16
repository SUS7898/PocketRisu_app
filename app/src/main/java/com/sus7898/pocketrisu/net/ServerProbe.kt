package com.sus7898.pocketrisu.net

import com.sus7898.pocketrisu.log.AppLog
import com.sus7898.pocketrisu.net.Http.await
import com.sus7898.pocketrisu.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** Reads the public server version endpoint; no credentials involved. Cancellable (T-9). */
object ServerProbe {
    private const val TAG = "Setup"
    private val json = Json { ignoreUnknownKeys = true }

    data class Info(val version: String, val deploymentType: String, val passwordSet: Boolean?)

    /** On success the result is also remembered in [settings] (used when a later launch cannot reach the server). */
    suspend fun probe(baseUrl: String, settings: AppSettings? = null): Result<Info> = runCatching {
        val req = Request.Builder().url("$baseUrl/api/update-check").header("User-Agent", Http.userAgentSuffix).build()
        val info = Http.probeClient.newCall(req).await().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            val body = withContext(Dispatchers.IO) { res.body!!.string() }
            val obj = json.parseToJsonElement(body).jsonObject
            Info(
                version = obj["currentVersion"]?.jsonPrimitive?.content ?: "?",
                deploymentType = obj["deploymentType"]?.jsonPrimitive?.content ?: "?",
                passwordSet = null,
            )
        }
        // /api/test_auth without a token answers 'unset' when no password is configured.
        val auth = runCatching {
            val r = Request.Builder().url("$baseUrl/api/test_auth").header("User-Agent", Http.userAgentSuffix).build()
            Http.probeClient.newCall(r).await().use { res ->
                val body = withContext(Dispatchers.IO) { res.body!!.string() }
                json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.content != "unset"
            }
        }.getOrNull()
        val full = info.copy(passwordSet = auth)
        settings?.rememberServer(full.version, full.deploymentType)
        AppLog.i(TAG, "server reachable version=${full.version} type=${full.deploymentType} passwordSet=${full.passwordSet}")
        full
    }.onFailure { AppLog.w(TAG, "server probe failed", it) }
}
