package com.sus7898.pocketrisu.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.sus7898.pocketrisu.log.AppLog

/** Receives PackageInstaller session status; forwards the system confirmation dialog when required. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                AppLog.i(TAG, "install needs user confirmation")
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Normally unreachable: the process is replaced by the new version before this fires.
                AppLog.i(TAG, "install success")
            }
            else -> {
                AppLog.e(TAG, "install failed status=$status message=$message")
                Toast.makeText(context, "업데이트 설치 실패: ${message ?: status}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val TAG = "Update"
        const val ACTION = "com.sus7898.pocketrisu.INSTALL_STATUS"
    }
}
