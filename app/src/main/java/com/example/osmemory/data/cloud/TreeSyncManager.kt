package com.example.osmemory.data.cloud

import android.content.Context
import com.example.osmemory.core.model.JsonTools
import com.example.osmemory.core.net.NetworkMonitor
import com.example.osmemory.core.pipeline.MemoryPipeline.Companion.LOG_COLLECT
import com.example.osmemory.core.pipeline.MemoryPipeline.Companion.LOG_INFER
import com.example.osmemory.data.db.dao.MemoryItemDao
import com.example.osmemory.data.db.dao.MemoryLogDao
import com.example.osmemory.data.db.entity.MemoryLogEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 本地树 ↔ 云端树 同步网关（对应 PPT 第 14 页 "Network Gateway / Pull"）
 *
 * 单向同步（本地优先，云端可拉取本地，本地不可 pull 云端）：
 *  - 在线：把本地树中"允许上云且非敏感"的记忆推送到云端树；敏感/保密记忆永不外发
 *    （内网保密记忆隔离：policyLevel=2 或 cloudEligible=false 的记忆只存在于本地树）。
 *  - 离线：Network Gateway 断开，同步暂停；云端树处于"不可达"状态。
 *
 * 本地树从不对云端树执行任何读取回填（本地不能 pull 云端内容），本类只向上写。
 */
class TreeSyncManager(
    private val context: Context,
    private val itemDao: MemoryItemDao,
    private val cloudDao: CloudMemoryItemDao,
    private val logDao: MemoryLogDao
) {

    /** 一次同步报告 */
    data class SyncReport(
        val online: Boolean,
        val pushed: Int,          // 成功推送到云端条数
        val skipped: Int,         // 因保密/敏感被隔离的条数
        val message: String,      // 展示用文案
        val at: Long
    ) {
        val succeeded: Boolean get() = online
    }

    private val _lastSync = MutableStateFlow<SyncReport?>(null)

    /** 最近一次同步结果（null = 尚未同步） */
    val lastSync: StateFlow<SyncReport?> = _lastSync

    /**
     * 执行一次 本地→云端 单向同步。
     * 在线且有任何可同步记忆才真正推送；否则返回报告并说明原因。
     */
    suspend fun sync(): SyncReport {
        val now = System.currentTimeMillis()
        if (!NetworkMonitor.isOnline(context)) {
            return report(
                online = false, pushed = 0, skipped = 0,
                message = "Network Gateway 断开（离线）：云端树不可达，本地树独立可用，同步已暂停",
                at = now
            ).also { _lastSync.value = it }
        }

        val candidates = itemDao.cloudSyncCandidates()
        if (candidates.isEmpty()) {
            return report(
                online = true, pushed = 0, skipped = 0,
                message = "在线：没有待同步的记忆（已全部同步，或所有记忆均为敏感/保密记忆）",
                at = now
            ).also { _lastSync.value = it }
        }

        var pushed = 0
        var skipped = 0
        val failedIds = mutableListOf<Long>()
        for (item in candidates) {
            // 保密隔离双保险：查询已过滤 policyLevel<2，这里再拦 cloudEligible（防御性）
            if (!item.cloudEligible || item.policyLevel >= 2) {
                skipped++
                continue
            }
            try {
                cloudDao.upsert(
                    CloudMemoryItemEntity(
                        memoId = item.memoId,
                        content = item.content,
                        title = item.title,
                        category = item.category,
                        tags = item.tags,
                        source = item.source,
                        appId = item.appId,
                        policyLevel = item.policyLevel,
                        confidence = item.confidence,
                        createdAt = item.createdAt,
                        updatedAt = item.updatedAt,
                        evidenceRaw = item.evidenceRaw,
                        syncedAt = now
                    )
                )
                itemDao.markSynced(item.id, now)
                pushed++
            } catch (e: Exception) {
                itemDao.markSyncFailed(item.id)
                failedIds += item.id
            }
        }

        if (failedIds.isNotEmpty()) {
            logDao.insert(
                MemoryLogEntity(
                    logType = LOG_INFER, action = "sync_fail",
                    appId = "osmemory_console", memoIds = failedIds.joinToString(","),
                    timestamp = now, source = "cloud",
                    contentSummary = "云端树同步失败 ${failedIds.size} 条（模拟网关写入异常）",
                    extra = JsonTools.buildJson("error" to "cloud upsert failed")
                )
            )
        }

        // 同步留痕（对应日志板块，审计可追踪"记忆去了云端"）
        logDao.insert(
            MemoryLogEntity(
                logType = LOG_COLLECT, action = "sync_cloud",
                appId = "osmemory_console",
                memoIds = if (pushed > 0) "pushed:$pushed" else "",
                timestamp = now, source = "cloud",
                contentSummary = "本地→云端单向同步：推送 $pushed 条，保密隔离 $skipped 条",
                tags = "",
                extra = JsonTools.buildJson(
                    "direction" to "local→cloud (pull only)",
                    "pushed" to pushed,
                    "secrecyIsolated" to skipped,
                    "gateway" to "online"
                )
            )
        )

        val message = when {
            pushed > 0 -> "在线：已推送 $pushed 条到云端树（保密隔离 $skipped 条），本地不能拉取云端内容"
            else -> "在线：无可推送记忆（保密隔离 $skipped 条）"
        }
        return report(online = true, pushed = pushed, skipped = skipped, message = message, at = now)
            .also { _lastSync.value = it }
    }

    private fun report(online: Boolean, pushed: Int, skipped: Int, message: String, at: Long) =
        SyncReport(online = online, pushed = pushed, skipped = skipped, message = message, at = at)
}
