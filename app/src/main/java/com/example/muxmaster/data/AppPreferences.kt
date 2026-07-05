package com.example.muxmaster.data

import android.content.Context
import android.net.Uri

class AppPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var defaultOutputFolder: Uri?
        get() = prefs.getString(KEY_OUTPUT_FOLDER, null)?.let {
            try { Uri.parse(it) } catch (_: Exception) { null }
        }
        set(value) {
            prefs.edit().putString(KEY_OUTPUT_FOLDER, value?.toString()).apply()
        }

    companion object {
        private const val PREFS_NAME = "muxmaster_prefs"
        private const val KEY_OUTPUT_FOLDER = "default_output_folder"
    }
}
