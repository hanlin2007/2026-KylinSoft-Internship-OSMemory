package com.example.osmemory.phase3.chat

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AlertDialog
import com.example.osmemory.R

/** 对话应用设置页：阶段三只有一个持久化记忆开关，外加一个演示前的记忆清空入口。 */
class ChatSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.phase3_chat_settings)

        val memorySwitch = findViewById<SwitchCompat>(R.id.p3_chat_memory_switch)
        memorySwitch.isChecked = ChatPreferences.isMemoryEnabled(this)
        memorySwitch.setOnCheckedChangeListener { _, enabled ->
            ChatPreferences.setMemoryEnabled(this, enabled)
        }

        val store = ChatMemoryStore(applicationContext)
        findViewById<Button>(R.id.p3_chat_settings_clear_memories).setOnClickListener {
            val count = store.all().size
            if (count == 0) {
                Toast.makeText(this, "没有需要清除的项目/会话记忆", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("清空项目/会话记忆")
                .setMessage("将清除 $count 条对话提炼记忆。OS Memory 中已同步的系统级记忆不受影响，可继续在控制台单独删除。")
                .setPositiveButton("清空") { _, _ ->
                    store.clear()
                    Toast.makeText(this, "已清空 $count 条对话记忆", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        findViewById<Button>(R.id.p3_chat_settings_back).setOnClickListener { finish() }
    }
}
