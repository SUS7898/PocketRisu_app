package com.sus7898.pocketrisu

import android.app.Application
import android.os.Build
import com.sus7898.pocketrisu.log.AppLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i(
            "App",
            "start ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE}) " +
                "on ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        // Share scratch files from a previous export are no longer needed.
        cacheDir.resolve("share").deleteRecursively()
    }
}
