package com.shiguang.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 全局可观察设置（Compose 状态驱动，改动即时生效并持久化） */
object AppSettings {
    var dataSaver by mutableStateOf(false)
        private set

    /** system / light / dark */
    var themeMode by mutableStateOf("system")
        private set

    fun init(context: android.content.Context) {
        val prefs = context.getSharedPreferences("shiguang_settings", android.content.Context.MODE_PRIVATE)
        dataSaver = prefs.getBoolean("data_saver", false)
        themeMode = prefs.getString("theme_mode", Session.themeMode) ?: "system"
    }

    fun setDataSaver(context: android.content.Context, v: Boolean) {
        dataSaver = v
        context.getSharedPreferences("shiguang_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("data_saver", v).apply()
    }

    fun setThemeMode(context: android.content.Context, v: String) {
        themeMode = v
        Session.themeMode = v
        context.getSharedPreferences("shiguang_settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("theme_mode", v).apply()
    }
}
