package com.sus7898.pocketrisu.ui

import android.content.Context
import android.content.Intent
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sus7898.pocketrisu.BuildConfig
import com.sus7898.pocketrisu.R
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
        b.menuSettings.setOnClickListener {
            dialog.dismiss()
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
        b.menuAbout.text = context.getString(
            R.string.settings_app_info,
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE, BuildConfig.VERIFIED_SERVER_VERSIONS,
        )
        dialog.setContentView(b.root)
        dialog.show()
    }
}
