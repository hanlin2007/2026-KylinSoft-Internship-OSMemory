package com.example.osmemory.phase3.api

import android.content.Context
import androidx.room.withTransaction
import com.example.osmemory.core.model.JsonTools
import com.example.osmemory.core.model.TextTools
import com.example.osmemory.core.pipeline.MemoryPipeline
import com.example.osmemory.data.MemoryService
import com.example.osmemory.data.db.OSMemoryDatabase
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryLogEntity

/**
 * 阶段三 MemoryService 系统 API 门面。
 *
 * 该门面只访问 Local Tree，负责应用幂等登记、scope 校验、普通应用敏感级上限和审计留痕。
 * UI 不接触 DAO、云树或网关；阶段四只需把本类调用替换为 Binder/AIDL 传输层。
 */
object MemoryApiService {
    fun client(context: Context, app: Phase3App): MemoryApiClient {
        val appContext = context.applicationContext
        val modelKeyInstalled = Phase3ModelBootstrap.installLocalKeyIfUnset(appContext)
        return MemoryApiClient(appContext, app).also { client ->
            if (modelKeyInstalled) client.refreshModelChannel()
        }
    }
}

class MemoryApiClient internal constructor(
    context: Context,
    val app: Phase3App
) {
    private val repository = MemoryService.repo(context)
    private val database = OSMemoryDatabase.get(context)
    private val itemDao = database.memoryItemDao()
    private val logDao = database.memoryLogDao()

    @Volatile
    private var registered = false

    internal fun refreshModelChannel() {
        repository.refreshChannels()
    }

    /** 应用登记是幂等操作；每个 API 也会在首次调用前自动登记。 */
    suspend fun register() {
        repository.registerApp(app.appId, app.displayName, app.scope.name)
        registered = true
    }

    /** memo_collect：只允许使用绑定身份的 appId/source，避免 UI 伪造调用方。 */
    suspend fun memoCollect(content: String): MemoCollectResult {
        ensureRegistered()
        return when (val result = repository.collect(content, app.source, app.appId)) {
            is MemoryPipeline.CollectResult.Success ->
                MemoCollectResult.Success(result.item.toApiMemo(), result.degraded)
            is MemoryPipeline.CollectResult.Duplicate ->
                MemoCollectResult.Duplicate(result.existing.toApiMemo())
            is MemoryPipeline.CollectResult.Rejected ->
                MemoCollectResult.Rejected(result.reason)
        }
    }

    /** memo_update：WRITE 应用只能修改自己创建的记忆。 */
    suspend fun memoUpdate(
        memoId: String,
        content: String,
        forceSecret: Boolean? = null
    ): MemoUpdateResult {
        ensureRegistered()
        val existing = itemDao.byMemoId(memoId)
            ?: return MemoUpdateResult.NotFound(memoId)
        if (existing.appId != app.appId) {
            return MemoUpdateResult.Forbidden("应用只能修改自己创建的记忆")
        }
        return when (
            val result = repository.updateItem(
                memoId = memoId,
                newContent = content,
                forceSecret = forceSecret,
                appId = app.appId
            )
        ) {
            is MemoryPipeline.UpdateResult.Success ->
                MemoUpdateResult.Success(result.item.toApiMemo(), result.degraded)
            is MemoryPipeline.UpdateResult.NotFound -> MemoUpdateResult.NotFound(result.memoId)
            is MemoryPipeline.UpdateResult.Rejected -> MemoUpdateResult.Rejected(result.reason)
        }
    }

    /** memo_delete：仅删除调用应用拥有的 Local Tree 记忆，并写入 COLLECT 审计日志。 */
    suspend fun memoDelete(memoId: String): MemoDeleteResult {
        ensureRegistered()
        val item = itemDao.byMemoId(memoId) ?: return MemoDeleteResult.NotFound(memoId)
        if (item.appId != app.appId) {
            return MemoDeleteResult.Forbidden("应用只能删除自己创建的记忆")
        }
        database.withTransaction {
            itemDao.delete(item)
            logDao.insert(
                MemoryLogEntity(
                    logType = MemoryPipeline.LOG_COLLECT,
                    action = "delete",
                    appId = app.appId,
                    memoIds = item.memoId,
                    timestamp = System.currentTimeMillis(),
                    source = app.source,
                    contentSummary = "应用删除关联记忆：${TextTools.truncate(item.title, 40)}",
                    tags = item.tags,
                    extra = JsonTools.buildJson("localTreeOnly" to true)
                )
            )
        }
        return MemoDeleteResult.Success
    }

    /**
     * get_memo：READ_WRITE 应用可读；policyMax 固定为 1，阶段三应用不能自行抬高敏感权限。
     */
    suspend fun getMemo(
        query: String,
        limit: Int = 10,
        semantic: Boolean = true
    ): List<MemoryMemo> {
        ensureReadScope()
        if (query.isBlank()) return emptyList()
        return repository.getMemo(
            appId = app.appId,
            query = query,
            limit = limit.coerceIn(1, MAX_READ_LIMIT),
            policyMax = NORMAL_APP_POLICY_MAX,
            semantic = semantic
        ).map { it.toApiMemo() }
    }

    /**
     * auto_recommend：返回 Local Tree 中可供场景代理编译上下文的近期记忆。
     *
     * 文件分类器据此读取标题、标签和正文摘要，再由统一 ModelProvider 动态生成开放类别；
     * 本方法不读取 Cloud Tree，也不扫描任何真实文件。
     */
    suspend fun autoRecommend(scene: String, limit: Int = 60): List<MemoryMemo> {
        ensureReadScope()
        val safeLimit = limit.coerceIn(1, MAX_RECOMMEND_LIMIT)
        val now = System.currentTimeMillis()
        val items = itemDao.allItems()
            .asSequence()
            .filter { it.policyLevel <= NORMAL_APP_POLICY_MAX }
            .filter { it.expiresAt == null || it.expiresAt > now }
            .sortedByDescending { it.updatedAt }
            .take(safeLimit)
            .toList()
        logDao.insert(
            MemoryLogEntity(
                logType = MemoryPipeline.LOG_RETRIEVE,
                action = "auto_recommend",
                appId = app.appId,
                memoIds = items.joinToString(",") { it.memoId },
                timestamp = now,
                source = app.source,
                contentSummary = "场景“${TextTools.truncate(scene, 30)}”→ 编译 ${items.size} 条本地记忆",
                extra = JsonTools.buildJson(
                    "tree" to "local",
                    "policyMax" to NORMAL_APP_POLICY_MAX,
                    "limit" to safeLimit
                )
            )
        )
        return items.map { it.toApiMemo() }
    }

    /** 为阶段三应用自身的模型调用留下 INFER 记录，避免 AI 行为游离在审计链路之外。 */
    suspend fun recordInference(
        action: String,
        summary: String,
        memoIds: List<String> = emptyList(),
        succeeded: Boolean = true,
        reason: String = ""
    ) {
        ensureRegistered()
        logDao.insert(
            MemoryLogEntity(
                logType = MemoryPipeline.LOG_INFER,
                action = action.take(40),
                appId = app.appId,
                memoIds = memoIds.joinToString(","),
                timestamp = System.currentTimeMillis(),
                source = app.source,
                contentSummary = TextTools.truncate(summary, 120),
                extra = JsonTools.buildJson(
                    "succeeded" to succeeded,
                    "reason" to TextTools.truncate(reason, 300)
                )
            )
        )
    }

    private suspend fun ensureRegistered() {
        if (!registered) register()
    }

    private suspend fun ensureReadScope() {
        ensureRegistered()
        if (app.scope != MemoryScope.READ_WRITE) {
            throw MemoryApiAccessException("${app.displayName} 仅有 WRITE 权限，不能读取记忆")
        }
    }

    companion object {
        const val NORMAL_APP_POLICY_MAX = 1
        const val MAX_READ_LIMIT = 20
        const val MAX_RECOMMEND_LIMIT = 100
    }
}

private fun MemoryItemEntity.toApiMemo(): MemoryMemo = MemoryMemo(
    memoId = memoId,
    content = content,
    title = title,
    category = category,
    tags = tags.split(',').map(String::trim).filter(String::isNotEmpty),
    source = source,
    appId = appId,
    policyLevel = policyLevel,
    createdAt = createdAt,
    updatedAt = updatedAt,
    confidence = confidence
)
