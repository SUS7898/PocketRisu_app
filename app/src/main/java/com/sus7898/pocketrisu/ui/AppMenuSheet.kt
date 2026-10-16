package com.sus7898.pocketrisu.ui

import android.content.Context
import android.content.Intent
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sus7898.pocketrisu.BuildConfig
import com.sus7898.pocketrisu.databinding.SheetAppMenuBinding

/** Native app menu (bottom sheet) opened from the edge handle in MainActivity. */
object AppMenuSheet {
    fun show(context: Context, onReload: () -> Unit) {
        val dialog = BottomSheetDialog(context)
        val b = SheetAppMenuBinding.inflate(dialog.layoutInflater)
        b.menuReload.setOnClickListener { dialog.dismiss(); onReload() }
        b.menuDiagnostics.setOnClickListener {
            dialog.dismiss()
            context.startActivity(Intent(context, DiagnosticsActivity::class.java))
        }
        b.menuServer.setOnClickListener {
            dialog.dismiss()
            context.startActivity(Intent(context, SetupActivity::class.java))
        }
        b.menuAbout.text = "PocketRisu App ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) · " +
            "verified with server ${BuildConfig.VERIFIED_SERVER_VERSIONS}"
        dialog.setContentView(b.root)
        dialog.show()
    }
}
