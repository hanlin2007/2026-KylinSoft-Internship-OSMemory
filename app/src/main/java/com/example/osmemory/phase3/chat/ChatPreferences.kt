package com.example.osmemory.phase3.chat

import android.content.Context

/** 对话应用唯一的用户设置：是否启用 OS Memory 读写链路。 */
object ChatPreferences {
    private const val PREFS_NAME = "phase3_chat_preferences"
    private const val KEY_MEMORY_ENABLED = "memory_enabled"

    fun isMemoryEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MEMORY_ENABLED, false)

    fun setMemoryEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MEMORY_ENABLED, enabled)
            .apply()
    }
}
