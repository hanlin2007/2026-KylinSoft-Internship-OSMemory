package com.example.osmemory.data.cloud

import android.content.Context
import com.example.osmemory.core.model.JsonTools
import com.example.osmemory.core.model.ModelProvider
import com.example.osmemory.core.model.TextTools
import com.example.osmemory.core.net.NetworkMonitor
import com.example.osmemory.core.pipeline.MemoryPipeline
import com.example.osmemory.core.pipeline.MemoryPipeline.Companion.LOG_COLLECT
import com.example.osmemory.core.pipeline.MemoryPipeline.Companion.LOG_INFER
import com.example.osmemory.core.pipeline.MemoryPipeline.Companion.LOG_SECURITY
import com.example.osmemory.core.pipeline.SecurityGate
import com.example.osmemory.core.pipeline.TextExtractor
import com.example.osmemory.data.db.dao.MemoryItemDao
import com.example.osmemory.data.db.dao.MemoryLogDao
import com.example.osmemory.data.db.entity.MemoryLogEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * 本地树 ↔ 云端树 同步网关（对应 PPT 第 14 页 "Network Gateway / Pull"，阶段 2 修复）
 *
 * 网关语义（对齐验收意见 v2）：
 *  - 联网/政企内网 = "云端状态"，云树权限开启。云端树 = 内网企业/政企云端库。
 *  - **每次网络切换（离线→在线）都自动拉取**本地"待同步"记忆；与本地记忆的
 *    「保密不迁移云端」选项联动：勾选保密或判定敏感（cloudEligible=false）的记忆永不外发。
 *  - 敏感判断与本地树完全一致（SecurityGate + LLM 取并集），不再一律标敏感。
 *  - 来源两分：[CloudMemoryItemEntity.ORIGIN_LOCAL_SYNC]（本地同步，保留原判断）
 *    与 [CloudMemoryItemEntity.ORIGIN_CLOUD_CREATE]（云端/内网创建，按内容判断）。
 *  - 单向数据流：云端可"拉取"本地，本地**永不**从云端读回；断网时云端树锁定不可查看。
 *
 * 三条写入路径：
 *  1. [autoIntegrateIfNeeded]：每次联网自动拉取待同步本地记忆（MainActivity 触发）；
 *  2. [sync]：抽屉手动"同步到云端"；
 *  3. [addToCloud]：云端树 FAB 直接添加记忆（云端创建，敏感判断与本地一致）。
 */
class TreeSyncManager(
    private val context: Context,
    private val itemDao: MemoryItemDao,
    private val cloudDao: CloudMemoryItemDao,
    private val logDao: MemoryLogDao,
    private val provider: ModelProvider,
    private val gate: SecurityGate = SecurityGate()
) {

    /** 一次同步/整合报告 */
    data class SyncReport(
        val online: Boolean,
        val pushed: Int,          // 成功推送到云端条数
        val skipped: Int,         // 因保密/敏感被隔离的条数
        val message: String,      // 展示用文案
        val at: Long
    ) {
        val succeeded: Boolean get() = online
    }

    /** 云端树 FAB 添加结果 */
    sealed interface CloudAddResult {
        data class Success(val item: CloudMemoryItemEntity, val degraded: Boolean) : CloudAddResult
        data class Rejected(val reason: String) : CloudAddResult
        data object Unreachable : CloudAddResult
    }

    private val _lastSync = MutableStateFlow<SyncReport?>(null)

    /** 最近一次同步/整合结果（null = 尚未执行） */
    val lastSync: StateFlow<SyncReport?> = _lastSync

    /**
     * 联网自动拉取（每次 离线→在线 网络切换时由 MainActivity 触发）：
     * 把本地"允许上云且待同步"的记忆拉取到云端树（敏感/保密记忆由 cloudEligible 标签隔离，永不外发）。
     * 与本地记忆的「保密不迁移云端」选项联动：勾选保密或判定敏感的记忆不会出现在候选中。
     */
    suspend fun autoIntegrateIfNeeded(): SyncReport {
        val now = System.currentTimeMillis()
        if (!NetworkMonitor.isOnline(context)) {
            return report(
                online = false, pushed = 0, skipped = 0,
                message = "Network Gateway 断开（离线）：云端树不可达，自动拉取暂停",
                at = now
            ).also { _lastSync.value = it }
        }

        val candidates = itemDao.cloudSyncCandidates()
        if (candidates.isEmpty()) {
            return report(
                online = true, pushed = 0, skipped = 0,
                message = "在线：无待同步记忆（均已同步，或全部为敏感/保密隔离记忆）",
                at = now
            ).also { _lastSync.value = it }
        }

        var pushed = 0
        val categories = LinkedHashSet<String>()
        val tags = LinkedHashSet<String>()
        for (item in candidates) {
            // 保密隔离双保险：查询已过滤 cloudEligible=false / 敏感项，这里再拦（防御性）
            if (!item.cloudEligible || item.policyLevel >= 2) continue
            cloudDao.upsert(
                CloudMemoryItemEntity(
                    memoId = item.memoId,
                    content = item.content,
                    title = item.title,
                    category = item.category,
                    tags = item.tags,
                    source = item.source,
                    appId = item.appId,
                    policyLevel = item.policyLevel, // 保留本地判断结果（来源=本地同步）
                    confidence = item.confidence,
                    createdAt = item.createdAt,
                    updatedAt = item.updatedAt,
                    evidenceRaw = item.evidenceRaw,
                    syncedAt = now
                )
            )
            itemDao.markSynced(item.id, now)
            categories += item.category
            tags += item.tags.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
            pushed++
        }

        val summary = buildString {
            append("按记忆检索标签拉取")
            if (tags.isNotEmpty()) append("：${tags.take(8).joinToString("、")}")
        }

        // 自动拉取留痕（INFER：拉取过程；来源=本地同步，敏感判断沿用本地）
        logDao.insert(
            MemoryLogEntity(
                logType = LOG_INFER, action = "cloud_auto_pull",
                appId = CONSOLE_APP_ID,
                memoIds = if (pushed > 0) "pulled:$pushed" else "",
                timestamp = now, source = "cloud",
                contentSummary = "联网自动拉取：$pushed 条本地待同步记忆到云端树（$summary）",
                tags = tags.take(10).joinToString(","),
                extra = JsonTools.buildJson(
                    "direction" to "local→cloud (auto pull)",
                    "pushed" to pushed,
                    "categories" to categories.joinToString(","),
                    "gateway" to "online"
                )
            )
        )

        val message = "在线：已自动拉取 $pushed 条待同步记忆到云端树（敏感/保密记忆已隔离）"
        return report(online = true, pushed = pushed, skipped = 0, message = message, at = now)
            .also { _lastSync.value = it }
    }

    /**
     * 手动同步（抽屉"同步到云端"）：在线时把"允许上云且未同步"的本地记忆推送到云端树。
     * 敏感判断沿用本地结果（来源=本地同步）；敏感/保密记忆永不外发。
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
                        policyLevel = item.policyLevel, // 保留本地判断结果
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
                    appId = CONSOLE_APP_ID, memoIds = failedIds.joinToString(","),
                    timestamp = now, source = "cloud",
                    contentSummary = "云端树同步失败 ${failedIds.size} 条（模拟网关写入异常）",
                    extra = JsonTools.buildJson("error" to "cloud upsert failed")
                )
            )
        }

        // 同步留痕（COLLECT；敏感判断沿用本地，敏感记忆本来就不会被选中）
        logDao.insert(
            MemoryLogEntity(
                logType = LOG_COLLECT, action = "sync_cloud",
                appId = CONSOLE_APP_ID,
                memoIds = if (pushed > 0) "pushed:$pushed" else "",
                timestamp = now, source = "cloud",
                contentSummary = "本地→云端单向同步：推送 $pushed 条，保密隔离 $skipped 条（敏感判断与本地一致）",
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
            pushed > 0 -> "在线：已推送 $pushed 条到云端树（敏感判断与本地一致；保密隔离 $skipped 条），本地不能拉取云端内容"
            else -> "在线：无可推送记忆（保密隔离 $skipped 条）"
        }
        return report(online = true, pushed = pushed, skipped = skipped, message = message, at = now)
            .also { _lastSync.value = it }
    }

    /**
     * 云端树 FAB 添加入口：直接在云端/内网环境中创建记忆（来源=云端创建）。
     * 敏感判断与本地流水线一致：SecurityGate 敏感词栅栏 与 LLM 敏感标记 取并集。
     * 断网时不可用。
     */
    suspend fun addToCloud(
        content: String,
        source: String,
        appId: String = CONSOLE_APP_ID
    ): CloudAddResult {
        if (!NetworkMonitor.isOnline(context)) return CloudAddResult.Unreachable

        val text = content.trim()
        if (text.isEmpty()) return CloudAddResult.Rejected("内容为空")
        if (text.length > MemoryPipeline.MAX_CONTENT_LENGTH) {
            return CloudAddResult.Rejected("内容过长（超过 ${MemoryPipeline.MAX_CONTENT_LENGTH} 字符）")
        }

        val now = System.currentTimeMillis()
        // 敏感判断（与本地一致）：确定性栅栏 + LLM 敏感标记取并集
        val gateResult = gate.evaluate(text)
        // 结构化抽取（复用本地流水线抽取器；模型失败降级原文）
        val (extracted, degraded, degradeReason) = try {
            Triple(TextExtractor.extract(provider, text), false, "")
        } catch (e: Exception) {
            Triple(TextExtractor.degraded(text, source), true, TextExtractor.reasonOf(e))
        }
        val sensitive = gateResult.sensitive || extracted.sensitivity

        val memoId = "MEMO-CLOUD-$now-${Random.nextInt(100000, 999999)}"
        val item = CloudMemoryItemEntity(
            memoId = memoId,
            content = text,
            title = extracted.title,
            category = extracted.category,
            tags = extracted.tags.joinToString(","),
            source = source,
            appId = appId,
            policyLevel = if (sensitive) 2 else 1, // 与本地一致：按内容判断
            confidence = extracted.confidence.toFloat(),
            createdAt = now,
            updatedAt = now,
            evidenceRaw = text,
            syncedAt = now
        )
        cloudDao.upsert(item)

        logDao.insert(
            MemoryLogEntity(
                logType = LOG_COLLECT, action = "cloud_add",
                appId = appId, memoIds = memoId, timestamp = now, source = source,
                contentSummary = "云端树添加记忆：${item.title}（${item.category}）",
                tags = item.tags,
                extra = JsonTools.buildJson(
                    "policyLevel" to item.policyLevel,
                    "degraded" to degraded,
                    "degradeReason" to degradeReason
                )
            )
        )
        logDao.insert(
            MemoryLogEntity(
                logType = LOG_INFER, action = "extract_cloud",
                appId = appId, memoIds = memoId, timestamp = now, source = source,
                contentSummary = if (degraded) "云端树结构化抽取：已降级原文入库（$degradeReason）"
                else "云端树结构化抽取：${item.title}（${item.category}）",
                tags = item.tags,
                extra = JsonTools.buildJson(
                    "model" to provider.name,
                    "confidence" to item.confidence,
                    "degraded" to degraded,
                    "degradeReason" to degradeReason
                )
            )
        )
        // 安全敏感性日志：仅内容判断为敏感时留痕（与本地一致）
        if (sensitive) {
            logDao.insert(
                MemoryLogEntity(
                    logType = LOG_SECURITY, action = "cloud_add_sensitive",
                    appId = appId, memoIds = memoId, timestamp = now, source = source,
                    contentSummary = "云端树添加敏感记忆：${item.title}（命中规则：${gateResult.matchedRules.joinToString(",").ifBlank { "AI标记" }}）",
                    tags = "敏感",
                    extra = JsonTools.buildJson(
                        "policyLevel" to 2,
                        "matchedRules" to gateResult.matchedRules.joinToString(","),
                        "degraded" to degraded
                    )
                )
            )
        }

        return CloudAddResult.Success(item, degraded)
    }

    private fun report(online: Boolean, pushed: Int, skipped: Int, message: String, at: Long) =
        SyncReport(online = online, pushed = pushed, skipped = skipped, message = message, at = at)

    private companion object {
        const val CONSOLE_APP_ID = "osmemory_console"
    }
}
