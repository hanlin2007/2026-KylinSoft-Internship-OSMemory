package com.example.osmemory.phase3.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 对话应用自己的“项目/会话记忆”存储。
 *
 * 这里保存模型逐轮提炼的原子记忆，OS Memory 则通过 memo_collect 保存一份系统级副本。
 * 两者刻意解耦，后续跨进程 API 替换不会影响对话应用内的列表与补偿同步。
 */
class ChatMemoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun all(): List<ChatMemoryRecord> = readRecords()

    @Synchronized
    fun append(content: String, projectName: String, sessionId: String): ChatMemoryRecord {
        val now = System.currentTimeMillis()
        val record = ChatMemoryRecord(
            localId = "CHAT-$now-${UUID.randomUUID().toString().take(8)}",
            content = content.trim(),
            projectName = projectName.trim(),
            sessionId = sessionId.trim(),
            createdAt = now,
            syncState = ChatMemorySyncState.PENDING
        )
        val records = readRecords().toMutableList().apply {
            add(record)
        }.takeLast(MAX_RECORDS)
        writeRecords(records)
        return record
    }

    /** 清空全部项目/会话记忆（演示前重置用）；返回被清除的条数。 */
    @Synchronized
    fun clear(): Int {
        val count = readRecords().size
        preferences.edit().remove(KEY_RECORDS).apply()
        return count
    }

    @Synchronized
    fun markSynced(localId: String, memoId: String) {
        update(localId) {
            it.copy(
                syncState = ChatMemorySyncState.SYNCED,
                systemMemoId = memoId,
                syncMessage = ""
            )
        }
    }

    @Synchronized
    fun markFailed(localId: String, reason: String) {
        update(localId) {
            it.copy(
                syncState = ChatMemorySyncState.FAILED,
                syncMessage = reason.trim().take(MAX_SYNC_MESSAGE_LENGTH)
            )
        }
    }

    private fun update(localId: String, transform: (ChatMemoryRecord) -> ChatMemoryRecord) {
        val records = readRecords().map { record ->
            if (record.localId == localId) transform(record) else record
        }
        writeRecords(records)
    }

    private fun readRecords(): List<ChatMemoryRecord> {
        val raw = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val localId = json.optString("localId").trim()
                    val content = json.optString("content").trim()
                    if (localId.isBlank() || content.isBlank()) continue
                    add(
                        ChatMemoryRecord(
                            localId = localId,
                            content = content,
                            projectName = json.optString("projectName", DEFAULT_PROJECT_NAME)
                                .trim().ifBlank { DEFAULT_PROJECT_NAME },
                            sessionId = json.optString("sessionId", "legacy-session")
                                .trim().ifBlank { "legacy-session" },
                            createdAt = json.optLong("createdAt", 0L),
                            syncState = ChatMemorySyncState.fromWireValue(
                                json.optString("syncState")
                            ),
                            systemMemoId = json.optString("systemMemoId").trim(),
                            syncMessage = json.optString("syncMessage").trim()
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeRecords(records: List<ChatMemoryRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("localId", record.localId)
                    put("content", record.content)
                    put("projectName", record.projectName)
                    put("sessionId", record.sessionId)
                    put("createdAt", record.createdAt)
                    put("syncState", record.syncState.wireValue)
                    put("systemMemoId", record.systemMemoId)
                    put("syncMessage", record.syncMessage)
                }
            )
        }
        preferences.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "phase3_chat_memory_store"
        const val KEY_RECORDS = "project_session_memories"
        const val MAX_RECORDS = 200
        const val MAX_SYNC_MESSAGE_LENGTH = 240
        const val DEFAULT_PROJECT_NAME = "默认项目"
    }
}

data class ChatMemoryRecord(
    val localId: String,
    val content: String,
    val projectName: String,
    val sessionId: String,
    val createdAt: Long,
    val syncState: ChatMemorySyncState,
    val systemMemoId: String = "",
    val syncMessage: String = ""
) {
    /** OS Memory 中保留层级来源，但仍以一条可解释自然语言原子记忆入库。 */
    fun systemMemoryText(): String = "项目“$projectName”的会话记忆：$content"
}

enum class ChatMemorySyncState(val wireValue: String) {
    PENDING("pending"),
    SYNCED("synced"),
    FAILED("failed");

    companion object {
        fun fromWireValue(value: String): ChatMemorySyncState =
            entries.firstOrNull { it.wireValue == value } ?: PENDING
    }
}
