package com.sus7898.pocketrisu.update

import com.sus7898.pocketrisu.BuildConfig
import com.sus7898.pocketrisu.log.AppLog
import com.sus7898.pocketrisu.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** Reads GitHub Releases of this app (docs/DESIGN.md §3.6). No credentials; public API. */
object UpdateChecker {
    private const val TAG = "Update"
    private val json = Json { ignoreUnknownKeys = true }

    enum class Channel { STABLE, NIGHTLY }

    data class Release(
        val channel: Channel,
        val tag: String,
        val versionName: String,
        val versionCode: Int,
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long,
        val sumsUrl: String?,
        val notes: String,
        val htmlUrl: String,
    ) {
        val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE
    }

    private fun endpoint(channel: Channel): String {
        val base = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO_OWNER}/${BuildConfig.UPDATE_REPO_NAME}/releases"
        return when (channel) {
            Channel.STABLE -> "$base/latest"
            Channel.NIGHTLY -> "$base/tags/nightly"
        }
    }

    suspend fun fetchLatest(channel: Channel): Result<Release> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(endpoint(channel))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", Http.userAgentSuffix)
                .build()
            val body = Http.client.newCall(req).execute().use { res ->
                if (res.code == 404) error("릴리스가 아직 없습니다 (${channel.name.lowercase()})")
                if (!res.isSuccessful) error("GitHub HTTP ${res.code}")
                res.body!!.string()
            }
            val obj = json.parseToJsonElement(body).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.content ?: error("tag_name missing")
            val name = obj["name"]?.jsonPrimitive?.content ?: tag
            val versionName = when (channel) {
                Channel.STABLE -> tag.removePrefix("v")
                // "nightly (0.1.0-dev.5+abc1234)"
                Channel.NIGHTLY -> Regex("""\((.+)\)""").find(name)?.groupValues?.get(1) ?: error("nightly name has no version: $name")
            }
            val assets = obj["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
            val apk = assets.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?: error("APK asset missing in release $tag")
            val sums = assets.firstOrNull { it["name"]?.jsonPrimitive?.content == "SHA256SUMS.txt" }
            Release(
                channel = channel,
                tag = tag,
                versionName = versionName,
                versionCode = VersionCode.from(versionName),
                apkName = apk["name"]!!.jsonPrimitive.content,
                apkUrl = apk["browser_download_url"]!!.jsonPrimitive.content,
                apkSize = apk["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1L,
                sumsUrl = sums?.get("browser_download_url")?.jsonPrimitive?.content,
                notes = obj["body"]?.jsonPrimitive?.content ?: "",
                htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: "",
            ).also {
                AppLog.i(TAG, "check channel=${channel.name} latest=${it.versionName} (code ${it.versionCode}) current=${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}) newer=${it.isNewer}")
            }
        }.onFailure { AppLog.w(TAG, "check failed channel=${channel.name}", it) }
    }
}
