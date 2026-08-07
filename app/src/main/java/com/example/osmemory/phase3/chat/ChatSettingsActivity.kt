package com.example.osmemory.phase3.chat

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.example.osmemory.R

/** 对话应用设置页：阶段三只有一个持久化记忆开关。 */
class ChatSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.phase3_chat_settings)

        val memorySwitch = findViewById<SwitchCompat>(R.id.p3_chat_memory_switch)
        memorySwitch.isChecked = ChatPreferences.isMemoryEnabled(this)
        memorySwitch.setOnCheckedChangeListener { _, enabled ->
            ChatPreferences.setMemoryEnabled(this, enabled)
        }

        findViewById<Button>(R.id.p3_chat_settings_back).setOnClickListener { finish() }
    }
}
