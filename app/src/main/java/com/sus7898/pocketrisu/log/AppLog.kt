package com.sus7898.pocketrisu.log

import android.content.Context
import android.util.Log
import com.sus7898.pocketrisu.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The only logging entry point of the app (docs/LOGGING.md).
 * - every message passes through [Redactor] first
 * - recent lines live in a ring buffer for the diagnostics screen
 * - I/W/E lines are appended to rotating files under filesDir/logs
 */
object AppLog {
    private const val RING_CAPACITY = 2_000
    private const val FILE_MAX_BYTES = 512L * 1024
    private const val FILE_COUNT = 5

    private val ring = LogBuffer(RING_CAPACITY)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "AppLog").apply { isDaemon = true } }
    private val stamp = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

    @Volatile
    private var logDir: File? = null

    fun init(context: Context) {
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
    }

    fun d(tag: String, msg: String) = log('D', tag, msg, null)
    fun i(tag: String, msg: String) = log('I', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log('E', tag, msg, t)

    private fun log(level: Char, tag: String, msg: String, t: Throwable?) {
        val safe = Redactor.redact(if (t == null) msg else "$msg — ${t.javaClass.simpleName}: ${Redactor.redact(t.message ?: "")}")
        val line = "${stamp.get()!!.format(Date())} $level/${tag.padEnd(9)} $safe"
        ring.add(line)
        if (BuildConfig.DEBUG) {
            when (level) {
                'D' -> Log.d(tag, safe)
                'I' -> Log.i(tag, safe)
                'W' -> Log.w(tag, safe)
                else -> Log.e(tag, safe)
            }
        }
        // Debug chatter stays in memory only; files hold what a user would export.
        if (level != 'D') writer.execute { appendToFile(line) }
    }

    fun snapshot(): List<String> = ring.snapshot()

    /** Full persisted log, oldest first, followed by nothing from the ring (the ring is a subset of files for I/W/E). */
    fun readFiles(): String {
        val dir = logDir ?: return ""
        val sb = StringBuilder()
        for (i in FILE_COUNT - 1 downTo 0) {
            val f = File(dir, "app-$i.log")
            if (f.exists()) sb.append(f.readText())
        }
        return sb.toString()
    }

    fun clear() {
        ring.clear()
        writer.execute {
            logDir?.listFiles()?.forEach { it.delete() }
        }
    }

    private fun appendToFile(line: String) {
        val dir = logDir ?: return
        try {
            val current = File(dir, "app-0.log")
            if (current.exists() && current.length() > FILE_MAX_BYTES) rotate(dir)
            current.appendText(line + "\n")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("AppLog", "append failed", e)
        }
    }

    private fun rotate(dir: File) {
        File(dir, "app-${FILE_COUNT - 1}.log").delete()
        for (i in FILE_COUNT - 2 downTo 0) {
            val f = File(dir, "app-$i.log")
            if (f.exists()) f.renameTo(File(dir, "app-${i + 1}.log"))
        }
    }
}
