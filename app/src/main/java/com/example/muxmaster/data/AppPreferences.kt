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

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "dark") ?: "dark"
        set(value) { prefs.edit().putString(KEY_THEME_MODE, value).apply() }

    /**
     * Klasör kuyruğunda kalınan video numarası.
     *
     * Aynı klasör yeniden seçildiğinde (uygulama kapatılıp açıldığında dahil) tek tek
     * ileri basmak gerekmez: kaldığın video numarasından devam edilir.
     */
    fun loadQueuePosition(folderKey: String): Int {
        if (folderKey.isBlank()) return 0
        val saved = prefs.getString(KEY_QUEUE_FOLDER, null) ?: return 0
        if (saved != folderKey) return 0
        return prefs.getInt(KEY_QUEUE_INDEX, 0)
    }

    fun saveQueuePosition(folderKey: String, index: Int) {
        if (folderKey.isBlank()) return
        prefs.edit()
            .putString(KEY_QUEUE_FOLDER, folderKey)
            .putInt(KEY_QUEUE_INDEX, index.coerceAtLeast(0))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "muxmaster_prefs"
        private const val KEY_OUTPUT_FOLDER = "default_output_folder"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_QUEUE_FOLDER = "queue_folder"
        private const val KEY_QUEUE_INDEX = "queue_index"
    }
}
