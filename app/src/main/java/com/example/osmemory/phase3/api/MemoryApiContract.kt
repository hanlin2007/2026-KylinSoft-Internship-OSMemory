package com.example.osmemory.phase3.api

/**
 * 阶段三应用身份。
 *
 * 当前三个 vibe 应用与 OS Memory 位于同一 APK，通过受身份约束的进程内门面接入；
 * 阶段四替换成 Binder/AIDL 客户端时，业务 Activity 不需要改变调用语义。
 */
enum class Phase3App(
    val appId: String,
    val displayName: String,
    val source: String,
    val scope: MemoryScope
) {
    NOTES("app_notes", "备忘录", "notes", MemoryScope.WRITE),
    CHAT("app_chat", "ChatBot", "chat", MemoryScope.READ_WRITE),
    CLASSIFIER("app_files", "文件分类管理器", "files", MemoryScope.READ_WRITE)
}

enum class MemoryScope {
    WRITE,
    READ_WRITE
}

/** 与 Room 实体解耦的系统 API DTO，便于阶段四直接序列化为 IPC 数据。 */
data class MemoryMemo(
    val memoId: String,
    val content: String,
    val title: String,
    val category: String,
    val tags: List<String>,
    val source: String,
    val appId: String,
    val policyLevel: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val confidence: Float
)

sealed interface MemoCollectResult {
    data class Success(val memory: MemoryMemo, val degraded: Boolean) : MemoCollectResult
    data class Duplicate(val memory: MemoryMemo) : MemoCollectResult
    data class Rejected(val reason: String) : MemoCollectResult
}

sealed interface MemoUpdateResult {
    data class Success(val memory: MemoryMemo, val degraded: Boolean) : MemoUpdateResult
    data class NotFound(val memoId: String) : MemoUpdateResult
    data class Forbidden(val reason: String) : MemoUpdateResult
    data class Rejected(val reason: String) : MemoUpdateResult
}

sealed interface MemoDeleteResult {
    data object Success : MemoDeleteResult
    data class NotFound(val memoId: String) : MemoDeleteResult
    data class Forbidden(val reason: String) : MemoDeleteResult
}

class MemoryApiAccessException(message: String) : IllegalStateException(message)
