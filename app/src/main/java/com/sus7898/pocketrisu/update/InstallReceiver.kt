package com.sus7898.pocketrisu.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.sus7898.pocketrisu.R
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
                // One UI's verifier (Auto Blocker / Play Protect) rejects the first self-update attempt
                // and lets the second through (docs/TROUBLESHOOTING.md T-8, T-10).
                val verifierBlocked = message?.contains("VERIFICATION", ignoreCase = true) == true ||
                    message?.contains("ABORTED", ignoreCase = true) == true
                val text = if (verifierBlocked) context.getString(R.string.update_blocked_retry_hint) else "업데이트 설치 실패: ${message ?: status}"
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val TAG = "Update"
        const val ACTION = "com.sus7898.pocketrisu.INSTALL_STATUS"
    }
}
