package com.example.osmemory.core.pipeline

import com.example.osmemory.core.model.JsonTools
import com.example.osmemory.core.model.ModelProvider
import com.example.osmemory.core.model.TextTools
import com.example.osmemory.data.db.dao.MemoryItemDao
import com.example.osmemory.data.db.dao.MemoryLogDao
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryLogEntity
import kotlin.random.Random

/**
 * 记忆处理流水线（对应 PPT 第 14 页 Memory Collection → Processing → Store）
 *
 * raw_memo → ① 净化 → ② 安全门控 → ③ LLM 结构化抽取（失败降级，原因必记录）→ ④ 去重 → ⑤ 入库 + 双日志
 *
 * 设计原则：
 * - 安全基线优先：AI 敏感标记与确定性栅栏取并集
 * - 降级优先但不静默：模型不可用不阻断核心链路，且降级原因（网络/HTTP/解析/超时）必须写入日志与诊断
 * - 全链路留痕：COLLECT（传入）+ INFER（推理）两条日志
 * - 双树接入：新记忆默认待同步（敏感记忆自动保密隔离，永不外发）
 */
class MemoryPipeline(
    private val itemDao: MemoryItemDao,
    private val logDao: MemoryLogDao,
    private val provider: ModelProvider,
    private val gate: SecurityGate = SecurityGate()
) {

    companion object {
        const val MAX_CONTENT_LENGTH = 5000
        /** 去重窗口：24 小时内同源同内容视为重复 */
        const val DEDUP_WINDOW_MS = 24L * 3600 * 1000
        const val CONSOLE_APP_ID = "osmemory_console"
        const val LOG_COLLECT = "COLLECT"
        const val LOG_RETRIEVE = "RETRIEVE"
        const val LOG_INFER = "INFER"

        /** 安全敏感性日志（阶段 2 修复）：仅记录"敏感"标签内容——敏感记忆的传入/修改/检索/云端添加 */
        const val LOG_SECURITY = "SECURITY"

        /** 云同步状态常量（与 MemoryItemEntity.syncState 对齐） */
        const val SYNC_LOCAL_ONLY = 0
        const val SYNC_PENDING = 1
        const val SYNC_DONE = 2
        const val SYNC_FAILED = 3
    }

    /** 收集结果 */
    sealed interface CollectResult {
        data class Success(val item: MemoryItemEntity, val degraded: Boolean) : CollectResult
        data class Duplicate(val existing: MemoryItemEntity) : CollectResult
        data class Rejected(val reason: String) : CollectResult
    }

    /** 更新结果（先画像后改，保留 memoId 与 createdAt） */
    sealed interface UpdateResult {
        data class Success(val item: MemoryItemEntity, val degraded: Boolean) : UpdateResult
        data class NotFound(val memoId: String) : UpdateResult
        data class Rejected(val reason: String) : UpdateResult
    }

    suspend fun collect(memoryText: String, source: String, appId: String = CONSOLE_APP_ID): CollectResult {
        // ① 净化（sanitize）
        val text = memoryText.trim()
        if (text.isEmpty()) return CollectResult.Rejected("内容为空")
        if (text.length > MAX_CONTENT_LENGTH) return CollectResult.Rejected("内容过长（超过 $MAX_CONTENT_LENGTH 字符）")

        val now = System.currentTimeMillis()

        // ② 安全门控（确定性栅栏）
        val gateResult = gate.evaluate(text)

        // ③ LLM 结构化抽取（失败降级，原因必记录）
        val (extracted, degraded, degradeReason) = try {
            Triple(TextExtractor.extract(provider, text), false, "")
        } catch (e: Exception) {
            Triple(TextExtractor.degraded(text, source), true, TextExtractor.reasonOf(e))
        }
        // AI 敏感标记与确定性栅栏取并集（安全基线优先，防漏报）
        val sensitive = gateResult.sensitive || extracted.sensitivity

        // ④ 去重：24h 内同源同归一化内容 → 拒绝
        val hash = TextTools.normalizeHash(text)
        val existing = itemDao.findByHash(hash, source, now - DEDUP_WINDOW_MS)
        if (existing != null) {
            logDao.insert(
                MemoryLogEntity(
                    logType = LOG_COLLECT, action = "reject_duplicate",
                    appId = appId, memoIds = existing.memoId, timestamp = now,
                    source = source,
                    contentSummary = "拒绝重复记忆：${TextTools.truncate(text, 40)}（命中 ${existing.memoId}）",
                    extra = JsonTools.buildJson("hash" to hash)
                )
            )
            return CollectResult.Duplicate(existing)
        }

        // ⑤ 入库（Atomic Card）
        val memoId = "MEMO-$now-${Random.nextInt(100000, 999999)}"
        // 敏感记忆默认保密隔离：不迁移云端（cloudEligible=false）；普通记忆默认待同步
        val secret = sensitive
        val item = MemoryItemEntity(
            memoId = memoId,
            contentHash = hash,
            content = text,
            title = extracted.title,
            category = extracted.category,
            tags = extracted.tags.joinToString(","),
            source = source,
            appId = appId,
            policyLevel = if (secret) 2 else 1,
            createdAt = now,
            updatedAt = now,
            confidence = extracted.confidence.toFloat(),
            evidenceRaw = text,
            cloudEligible = !secret,
            syncState = if (secret) SYNC_LOCAL_ONLY else SYNC_PENDING
        )
        itemDao.insert(item)

        // ⑥ 双日志：传入 + 推理（降级原因必可见）
        logDao.insert(
            MemoryLogEntity(
                logType = LOG_COLLECT, action = "add",
                appId = appId, memoIds = memoId, timestamp = now,
                source = source,
                contentSummary = TextTools.truncate(text, 80),
                tags = extracted.tags.joinToString(","),
                extra = JsonTools.buildJson(
                    "policyLevel" to item.policyLevel,
                    "matchedRules" to gateResult.matchedRules.joinToString(","),
                    "degraded" to degraded,
                    "degradeReason" to degradeReason,
                    "cloudEligible" to item.cloudEligible,
                    "syncState" to item.syncState
                )
            )
        )
        logDao.insert(
            MemoryLogEntity(
                logType = LOG_INFER, action = "extract",
                appId = appId, memoIds = memoId, timestamp = now,
                source = source,
                contentSummary = if (degraded) "结构化抽取：已降级原文入库（${degradeReason}）"
                else "结构化抽取：${extracted.title}（${extracted.category}）",
                tags = extracted.tags.joinToString(","),
                extra = JsonTools.buildJson(
                    "model" to provider.name,
                    "confidence" to extracted.confidence,
                    "entities" to extracted.entities.joinToString(","),
                    "degraded" to degraded,
                    "degradeReason" to degradeReason,
                    "llmSensitivity" to extracted.sensitivity
                )
            )
        )

        // ⑦ 安全敏感性日志：仅敏感记忆记录（安全门控命中 / AI 敏感标记 → 取并集）
        if (secret) {
            logDao.insert(
                MemoryLogEntity(
                    logType = LOG_SECURITY, action = "sensitive_add",
                    appId = appId, memoIds = memoId, timestamp = now,
                    source = source,
                    contentSummary = "敏感记忆入库：${extracted.title}（命中规则：${gateResult.matchedRules.joinToString(",").ifBlank { "AI标记" }}）",
                    tags = "敏感",
                    extra = JsonTools.buildJson(
                        "policyLevel" to 2,
                        "matchedRules" to gateResult.matchedRules.joinToString(","),
                        "llmSensitivity" to extracted.sensitivity,
                        "degraded" to degraded
                    )
                )
            )
        }

        return CollectResult.Success(item, degraded)
    }

    /**
     * 记忆修改（阶段 2："先画像后改"）。
     * 保留 memoId 与 createdAt，重跑 净化→门控→抽取，更新内容/标题/分类/标签/置信度，
     * updatedAt 刷新，syncState 重置为待同步（云端需重新拉取），留 COLLECT(update) + INFER(extract) 日志。
     */
    suspend fun update(
        memoId: String,
        newContent: String,
        source: String? = null,
        appId: String = CONSOLE_APP_ID,
        forceSecret: Boolean? = null
    ): UpdateResult {
        val existing = itemDao.byMemoId(memoId) ?: return UpdateResult.NotFound(memoId)

        val text = newContent.trim()
        if (text.isEmpty()) return UpdateResult.Rejected("内容为空")
        if (text.length > MAX_CONTENT_LENGTH) return UpdateResult.Rejected("内容过长（超过 $MAX_CONTENT_LENGTH 字符）")

        val now = System.currentTimeMillis()
        val gateResult = gate.evaluate(text)

        val (extracted, degraded, degradeReason) = try {
            Triple(TextExtractor.extract(provider, text), false, "")
        } catch (e: Exception) {
            Triple(TextExtractor.degraded(text, existing.source), true, TextExtractor.reasonOf(e))
        }
        val sensitive = gateResult.sensitive || extracted.sensitivity
        // 用户可显式指定保密/上云；否则沿用原记忆的 cloudEligible（敏感则强制隔离）
        val cloudEligible = when {
            sensitive -> false
            forceSecret != null -> !forceSecret
            else -> existing.cloudEligible
        }
        val hash = TextTools.normalizeHash(text)

        val updated = existing.copy(
            contentHash = hash,
            content = text,
            title = extracted.title,
            category = extracted.category,
            tags = extracted.tags.joinToString(","),
            source = source ?: existing.source,
            policyLevel = if (sensitive) 2 else 1,
            updatedAt = now,
            confidence = extracted.confidence.toFloat(),
            evidenceRaw = text,
            cloudEligible = cloudEligible,
            syncState = if (sensitive || !cloudEligible) SYNC_LOCAL_ONLY else SYNC_PENDING,
            syncedAt = null
        )
        itemDao.update(updated)

        logDao.insert(
            MemoryLogEntity(
                logType = LOG_COLLECT, action = "update",
                appId = appId, memoIds = memoId, timestamp = now,
                source = updated.source,
                contentSummary = "修改记忆：${updated.title}（${updated.memoId}）",
                tags = updated.tags,
                extra = JsonTools.buildJson(
                    "policyLevel" to updated.policyLevel,
                    "degraded" to degraded,
                    "degradeReason" to degradeReason,
                    "cloudEligible" to updated.cloudEligible,
                    "syncState" to updated.syncState
                )
            )
        )
        logDao.insert(
            MemoryLogEntity(
                logType = LOG_INFER, action = "extract",
                appId = appId, memoIds = memoId, timestamp = now,
                source = updated.source,
                contentSummary = if (degraded) "修改后结构化抽取：已降级（${degradeReason}）"
                else "修改后结构化抽取：${updated.title}（${updated.category}）",
                tags = updated.tags,
                extra = JsonTools.buildJson(
                    "model" to provider.name,
                    "confidence" to updated.confidence,
                    "degraded" to degraded,
                    "degradeReason" to degradeReason
                )
            )
        )

        // 安全敏感性日志：修改后仍是/变为敏感的记忆
        if (sensitive) {
            logDao.insert(
                MemoryLogEntity(
                    logType = LOG_SECURITY, action = "sensitive_update",
                    appId = appId, memoIds = memoId, timestamp = now,
                    source = updated.source,
                    contentSummary = "敏感记忆修改：${updated.title}（命中规则：${gateResult.matchedRules.joinToString(",").ifBlank { "AI标记" }}）",
                    tags = "敏感",
                    extra = JsonTools.buildJson(
                        "policyLevel" to 2,
                        "matchedRules" to gateResult.matchedRules.joinToString(","),
                        "llmSensitivity" to extracted.sensitivity,
                        "degraded" to degraded
                    )
                )
            )
        }

        return UpdateResult.Success(updated, degraded)
    }
}
