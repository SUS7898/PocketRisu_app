package com.sus7898.pocketrisu.net

import com.sus7898.pocketrisu.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** Reads the public server version endpoint; no credentials involved. */
object ServerProbe {
    private const val TAG = "Setup"
    private val json = Json { ignoreUnknownKeys = true }

    data class Info(val version: String, val deploymentType: String, val passwordSet: Boolean?)

    suspend fun probe(baseUrl: String): Result<Info> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$baseUrl/api/update-check").header("User-Agent", Http.userAgentSuffix).build()
            val info = Http.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) error("HTTP ${res.code}")
                val obj = json.parseToJsonElement(res.body!!.string()).jsonObject
                Info(
                    version = obj["currentVersion"]?.jsonPrimitive?.content ?: "?",
                    deploymentType = obj["deploymentType"]?.jsonPrimitive?.content ?: "?",
                    passwordSet = null,
                )
            }
            // /api/test_auth without a token answers 'unset' when no password is configured.
            val auth = runCatching {
                val r = Request.Builder().url("$baseUrl/api/test_auth").header("User-Agent", Http.userAgentSuffix).build()
                Http.client.newCall(r).execute().use { res ->
                    val obj = json.parseToJsonElement(res.body!!.string()).jsonObject
                    obj["status"]?.jsonPrimitive?.content != "unset"
                }
            }.getOrNull()
            val full = info.copy(passwordSet = auth)
            AppLog.i(TAG, "server reachable version=${full.version} type=${full.deploymentType} passwordSet=${full.passwordSet}")
            full
        }.onFailure { AppLog.w(TAG, "server probe failed", it) }
    }
}
