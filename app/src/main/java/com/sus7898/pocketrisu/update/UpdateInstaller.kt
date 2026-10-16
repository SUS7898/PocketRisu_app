package com.sus7898.pocketrisu.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.sus7898.pocketrisu.log.AppLog
import com.sus7898.pocketrisu.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Download → verify (SHA-256 from SHA256SUMS.txt, then signer certificate == this app's) → PackageInstaller.
 * A file that fails either check is deleted and never handed to the installer (docs/DESIGN.md §3.6).
 */
class UpdateInstaller(private val context: Context) {
    private val dir = File(context.cacheDir, "updates").apply { mkdirs() }

    class VerificationException(message: String) : Exception(message)

    suspend fun download(release: UpdateChecker.Release, onProgress: (done: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, release.apkName)
            val req = Request.Builder().url(release.apkUrl).header("User-Agent", Http.userAgentSuffix).build()
            Http.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) error("download HTTP ${res.code}")
                val body = res.body ?: error("empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 256 * 1024) { lastReport = done; onProgress(done, total) }
                        }
                        onProgress(done, total)
                    }
                }
            }
            AppLog.i(TAG, "downloaded ${release.apkName} bytes=${target.length()}")
            target
        }

    /** Throws [VerificationException] on mismatch; the file is deleted first. */
    suspend fun verify(file: File, release: UpdateChecker.Release) = withContext(Dispatchers.IO) {
        try {
            val sumsUrl = release.sumsUrl ?: throw VerificationException("SHA256SUMS.txt not published for ${release.tag}")
            val sums = Request.Builder().url(sumsUrl).header("User-Agent", Http.userAgentSuffix).build().let { req ->
                Http.client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw VerificationException("SHA256SUMS HTTP ${res.code}")
                    res.body!!.string()
                }
            }
            val expected = sums.lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { it.size >= 2 && it[1].removePrefix("*") == release.apkName }
                ?.get(0)?.lowercase()
                ?: throw VerificationException("${release.apkName} not listed in SHA256SUMS.txt")
            val actual = sha256(file)
            if (actual != expected) throw VerificationException("sha256 mismatch")
            AppLog.i(TAG, "sha256 ok ${actual.take(12)}…")

            val ownCert = signerDigest(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
            val apkInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: throw VerificationException("APK could not be parsed")
            val apkCert = signerDigest(apkInfo)
            if (ownCert == null || apkCert != ownCert) throw VerificationException("signer mismatch")
            AppLog.i(TAG, "signer ok ${apkCert?.take(12)}…")
        } catch (e: Exception) {
            file.delete()
            AppLog.e(TAG, "verification failed", e)
            throw e
        }
    }

    /** Hands the verified file to the system installer. Progress continues in [InstallReceiver]. */
    fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) {
                // Honoured only once this app is the installer of record (after its first self-update).
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("app.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            AppLog.i(TAG, "install session=$sessionId committed")
            session.commit(pending.intentSender)
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun signerDigest(info: android.content.pm.PackageInfo): String? {
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        val first = signers.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(first.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "Update"
    }
}
