package com.example.osmemory.phase3.chat

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.osmemory.R
import com.example.osmemory.core.model.ModelManager
import com.example.osmemory.phase3.api.MemoCollectResult
import com.example.osmemory.phase3.api.MemoryApiClient
import com.example.osmemory.phase3.api.MemoryApiService
import com.example.osmemory.phase3.api.MemoryMemo
import com.example.osmemory.phase3.api.Phase3App
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阶段三对话问答：普通聊天 + 可插拔的 Local Tree 检索与项目/会话原子记忆写入。
 */
class ChatActivity : AppCompatActivity() {
    private lateinit var memoryApi: MemoryApiClient
    private val chatMemoryStore by lazy { ChatMemoryStore(applicationContext) }
    private val syncingLocalIds = mutableSetOf<String>()

    private lateinit var conversationScroll: ScrollView
    private lateinit var conversationContainer: LinearLayout
    private lateinit var projectMemoryScroll: ScrollView
    private lateinit var projectMemoryContainer: LinearLayout
    private lateinit var memoryModeText: TextView
    private lateinit var input: EditText
    private lateinit var sendButton: Button

    private var memoryEnabled = false
    private var sending = false
    private lateinit var sessionId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.phase3_chat_activity)
        memoryApi = MemoryApiService.client(applicationContext, Phase3App.CHAT)
        sessionId = savedInstanceState?.getString(STATE_SESSION_ID)
            ?: "SESSION-${System.currentTimeMillis().toString(36).uppercase()}"

        conversationScroll = findViewById(R.id.p3_chat_conversation_scroll)
        conversationContainer = findViewById(R.id.p3_chat_conversation_container)
        projectMemoryScroll = findViewById(R.id.p3_chat_project_memory_scroll)
        projectMemoryContainer = findViewById(R.id.p3_chat_project_memory_container)
        memoryModeText = findViewById(R.id.p3_chat_memory_mode)
        input = findViewById(R.id.p3_chat_input)
        sendButton = findViewById(R.id.p3_chat_send)

        findViewById<Button>(R.id.p3_chat_settings).setOnClickListener {
            startActivity(Intent(this, ChatSettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.p3_chat_project_memory_clear).setOnClickListener {
            confirmClearProjectMemories()
        }
        sendButton.setOnClickListener { submitQuestion() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitQuestion()
                true
            } else {
                false
            }
        }

        appendAssistantMessage(
            reply = "你好，我是对话问答助手。你可以在右上角设置中开启记忆。",
            references = emptyList(),
            memoryWasEnabled = false
        )
        renderProjectMemories()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SESSION_ID, sessionId)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        memoryEnabled = ChatPreferences.isMemoryEnabled(this)
        memoryModeText.text = if (memoryEnabled) {
            "记忆已开启 · 本地检索 + 项目/会话记忆"
        } else {
            "记忆已关闭 · 普通 AI 对话"
        }
        memoryModeText.setTextColor(
            if (memoryEnabled) Color.rgb(27, 94, 32) else Color.rgb(97, 97, 97)
        )
        if (memoryEnabled) retryPendingMemories()
        // 从设置页清空记忆后返回时刷新面板
        renderProjectMemories()
    }

    /** 清空记忆面板：二次确认后清空项目/会话记忆并立即重绘（OS Memory 系统级副本不受影响） */
    private fun confirmClearProjectMemories() {
        val count = chatMemoryStore.all().size
        if (count == 0) {
            Toast.makeText(this, "记忆面板已经是空的", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清空项目/会话记忆")
            .setMessage("将清除面板中的 $count 条对话提炼记忆。已同步到 OS Memory 的系统级副本不受影响。")
            .setPositiveButton("清空") { _, _ ->
                val cleared = chatMemoryStore.clear()
                renderProjectMemories()
                Toast.makeText(this, "已清空 $cleared 条对话记忆", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun submitQuestion() {
        val question = input.text.toString().trim()
        if (question.isBlank() || sending) return

        val useMemoryForTurn = memoryEnabled
        appendUserMessage(question)
        input.text?.clear()
        hideKeyboard()
        setSending(true)

        lifecycleScope.launch {
            var references = emptyList<MemoryMemo>()
            var retrievalWarning = ""
            try {
                if (useMemoryForTurn) {
                    val retrieval = runCatching {
                        memoryApi.getMemo(query = question, limit = 5, semantic = true)
                    }
                    references = retrieval.getOrDefault(emptyList())
                    retrievalWarning = retrieval.exceptionOrNull()?.message.orEmpty()
                }

                val promptMemories = references.map { memory ->
                    ChatPromptMemory(
                        memoId = memory.memoId,
                        title = memory.title,
                        content = memory.content,
                        tags = memory.tags
                    )
                }
                val rawResponse = ModelManager.provider(applicationContext).complete(
                    system = ChatPromptBuilder.systemPrompt(useMemoryForTurn),
                    user = ChatPromptBuilder.userPrompt(question, promptMemories),
                    temperature = 0.35
                )
                val response = ChatResponseParser.parse(rawResponse)

                if (useMemoryForTurn) {
                    runCatching {
                        memoryApi.recordInference(
                            action = "chat_answer",
                            summary = "回答“${question.take(60)}”，引用 ${references.size} 条本地记忆",
                            memoIds = references.map(MemoryMemo::memoId),
                            succeeded = true,
                            reason = buildString {
                                if (!response.structured) append("模型输出不是约定 JSON；已安全展示原文")
                                if (retrievalWarning.isNotBlank()) {
                                    if (isNotEmpty()) append("；")
                                    append("检索异常：${retrievalWarning.take(180)}")
                                }
                            }
                        )
                    }
                }

                appendAssistantMessage(response.reply, references, useMemoryForTurn)
                if (useMemoryForTurn) {
                    response.memories.forEach { atomicMemory ->
                        val localRecord = chatMemoryStore.append(
                            content = atomicMemory,
                            projectName = PROJECT_NAME,
                            sessionId = sessionId
                        )
                        renderProjectMemories()
                        syncMemory(localRecord)
                    }
                }
            } catch (error: Throwable) {
                if (useMemoryForTurn) {
                    runCatching {
                        memoryApi.recordInference(
                            action = "chat_answer",
                            summary = "回答“${question.take(60)}”失败",
                            memoIds = references.map(MemoryMemo::memoId),
                            succeeded = false,
                            reason = error.message ?: error.javaClass.simpleName
                        )
                    }
                }
                // Throwable：模型异常/OOM 等 Error 也转为错误气泡，绝不闪退
                appendErrorMessage(error.message ?: "模型调用失败，请稍后重试")
            } finally {
                setSending(false)
            }
        }
    }

    /** 本地落盘后立刻启动系统记忆通信；Pending 项也会在 Activity 重建后补偿。 */
    private fun syncMemory(record: ChatMemoryRecord) {
        if (!syncingLocalIds.add(record.localId)) return
        lifecycleScope.launch {
            try {
                when (val result = memoryApi.memoCollect(record.systemMemoryText())) {
                    is MemoCollectResult.Success ->
                        chatMemoryStore.markSynced(record.localId, result.memory.memoId)
                    is MemoCollectResult.Duplicate ->
                        chatMemoryStore.markSynced(record.localId, result.memory.memoId)
                    is MemoCollectResult.Rejected ->
                        chatMemoryStore.markFailed(record.localId, result.reason)
                }
            } catch (error: Throwable) {
                runCatching {
                    chatMemoryStore.markFailed(
                        record.localId,
                        error.message ?: error.javaClass.simpleName
                    )
                }
            } finally {
                syncingLocalIds.remove(record.localId)
                runCatching { renderProjectMemories() }
            }
        }
    }

    private fun retryPendingMemories() {
        chatMemoryStore.all()
            .filter { it.syncState == ChatMemorySyncState.PENDING }
            .forEach(::syncMemory)
    }

    private fun setSending(value: Boolean) {
        sending = value
        sendButton.isEnabled = !value
        input.isEnabled = !value
        sendButton.text = if (value) "思考中…" else "发送"
    }

    private fun appendUserMessage(message: String) {
        val text = newBubble(
            text = "我\n$message",
            backgroundColor = Color.rgb(63, 81, 181),
            textColor = Color.WHITE,
            gravity = Gravity.END
        )
        conversationContainer.addView(text)
        scrollConversationToBottom()
    }

    private fun appendAssistantMessage(
        reply: String,
        references: List<MemoryMemo>,
        memoryWasEnabled: Boolean
    ) {
        val referenceText = when {
            !memoryWasEnabled -> "记忆关闭 · 本轮未检索"
            references.isEmpty() -> "本轮引用：无匹配记忆"
            else -> references.joinToString(
                separator = "\n",
                prefix = "本轮引用：\n"
            ) { memory -> "• ${memory.title} · ${memory.memoId}" }
        }
        val text = newBubble(
            text = "AI\n$reply\n\n$referenceText",
            backgroundColor = Color.rgb(238, 238, 245),
            textColor = Color.rgb(32, 33, 36),
            gravity = Gravity.START
        )
        conversationContainer.addView(text)
        scrollConversationToBottom()
    }

    private fun appendErrorMessage(message: String) {
        val text = newBubble(
            text = "调用失败\n$message",
            backgroundColor = Color.rgb(255, 235, 238),
            textColor = Color.rgb(183, 28, 28),
            gravity = Gravity.START
        )
        conversationContainer.addView(text)
        scrollConversationToBottom()
        Toast.makeText(this, "AI 回答失败", Toast.LENGTH_SHORT).show()
    }

    private fun newBubble(
        text: String,
        backgroundColor: Int,
        textColor: Int,
        gravity: Int
    ): TextView = TextView(this).apply {
        this.text = text
        this.setTextColor(textColor)
        textSize = 15f
        setLineSpacing(0f, 1.12f)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.gravity = gravity
            setMargins(
                if (gravity == Gravity.END) dp(48) else 0,
                dp(6),
                if (gravity == Gravity.START) dp(48) else 0,
                dp(6)
            )
        }
    }

    private fun renderProjectMemories() {
        projectMemoryContainer.removeAllViews()
        val records = chatMemoryStore.all()
        if (records.isEmpty()) {
            projectMemoryContainer.addView(TextView(this).apply {
                text = "开启记忆后，模型提炼的原子记忆会逐条显示在这里"
                textSize = 13f
                setTextColor(Color.rgb(117, 117, 117))
                setPadding(dp(12), dp(8), dp(12), dp(8))
            })
            return
        }

        records.forEach { record ->
            projectMemoryContainer.addView(TextView(this).apply {
                text = buildString {
                    append("• ${record.content}")
                    append("\n项目：${record.projectName} · 会话：${record.sessionId.takeLast(8)}")
                    append("\n${formatTime(record.createdAt)} · ")
                    when (record.syncState) {
                        ChatMemorySyncState.PENDING -> append("正在同步 OS Memory")
                        ChatMemorySyncState.SYNCED -> append("已同步 · ${record.systemMemoId}")
                        ChatMemorySyncState.FAILED -> {
                            append("同步失败")
                            if (record.syncMessage.isNotBlank()) append(" · ${record.syncMessage}")
                        }
                    }
                }
                textSize = 13f
                setTextColor(Color.rgb(48, 48, 48))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                if (record.syncState == ChatMemorySyncState.FAILED) {
                    setOnClickListener {
                        if (memoryEnabled) {
                            syncMemory(record.copy(syncState = ChatMemorySyncState.PENDING))
                            Toast.makeText(this@ChatActivity, "正在重试同步", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@ChatActivity, "请先在设置中开启记忆", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
        }
        projectMemoryScroll.post { projectMemoryScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun scrollConversationToBottom() {
        conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PROJECT_NAME = "默认项目"
        const val STATE_SESSION_ID = "phase3.chat.session_id"
    }
}
