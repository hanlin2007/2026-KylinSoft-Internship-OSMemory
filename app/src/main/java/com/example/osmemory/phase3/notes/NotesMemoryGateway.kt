package com.example.osmemory.phase3.notes

import android.content.Context
import com.example.osmemory.phase3.api.MemoCollectResult
import com.example.osmemory.phase3.api.MemoDeleteResult
import com.example.osmemory.phase3.api.MemoUpdateResult
import com.example.osmemory.phase3.api.MemoryApiService
import com.example.osmemory.phase3.api.Phase3App
import kotlinx.coroutines.CancellationException

/**
 * The only OS Memory dependency of the notes mini-app.
 *
 * Keeping the service result mapping here makes the UI and local JSON store
 * independent from the phase-2 repository and from a future Binder transport.
 */
internal class NotesMemoryGateway(context: Context) {
    private val client = MemoryApiService.client(context.applicationContext, Phase3App.NOTES)

    suspend fun collect(content: String): CollectOutcome = try {
        when (val result = client.memoCollect(content)) {
            is MemoCollectResult.Success -> CollectOutcome.Linked(
                memoId = result.memory.memoId,
                message = "已创建关联记忆"
            )

            is MemoCollectResult.Duplicate -> CollectOutcome.Failed(
                "相同内容已存在于 OS Memory；为避免多条记录共享并误删同一记忆，本记录保持未关联"
            )

            is MemoCollectResult.Rejected -> CollectOutcome.Failed(result.reason)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        CollectOutcome.Failed(error.readableMessage())
    }

    suspend fun update(memoId: String, content: String): UpdateOutcome = try {
        when (val result = client.memoUpdate(memoId, content)) {
            is MemoUpdateResult.Success -> UpdateOutcome.Updated
            is MemoUpdateResult.NotFound -> UpdateOutcome.NotFound
            is MemoUpdateResult.Forbidden -> UpdateOutcome.Failed(result.reason)
            is MemoUpdateResult.Rejected -> UpdateOutcome.Failed(result.reason)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        UpdateOutcome.Failed(error.readableMessage())
    }

    suspend fun delete(memoId: String): DeleteOutcome = try {
        when (val result = client.memoDelete(memoId)) {
            MemoDeleteResult.Success -> DeleteOutcome.Deleted
            is MemoDeleteResult.NotFound -> DeleteOutcome.NotFound
            is MemoDeleteResult.Forbidden -> DeleteOutcome.Failed(result.reason)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        DeleteOutcome.Failed(error.readableMessage())
    }

    internal sealed interface CollectOutcome {
        data class Linked(val memoId: String, val message: String) : CollectOutcome
        data class Failed(val reason: String) : CollectOutcome
    }

    internal sealed interface UpdateOutcome {
        data object Updated : UpdateOutcome
        data object NotFound : UpdateOutcome
        data class Failed(val reason: String) : UpdateOutcome
    }

    internal sealed interface DeleteOutcome {
        data object Deleted : DeleteOutcome
        data object NotFound : DeleteOutcome
        data class Failed(val reason: String) : DeleteOutcome
    }

    private fun Exception.readableMessage(): String =
        message?.takeIf(String::isNotBlank) ?: "OS Memory 暂时不可用"
}
