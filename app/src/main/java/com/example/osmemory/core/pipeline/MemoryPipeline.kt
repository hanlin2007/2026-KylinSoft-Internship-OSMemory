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
 * raw_memo → ① 净化 → ② 安全门控 → ③ LLM 结构化抽取（失败降级）→ ④ 去重 → ⑤ 入库 + 双日志
 *
 * 设计原则：
 * - 安全基线优先：AI 敏感标记与确定性栅栏取并集
 * - 降级优先：模型不可用不阻断核心链路（收集/存储是硬能力）
 * - 全链路留痕：COLLECT（传入）+ INFER（推理）两条日志
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
    }

    /** 收集结果 */
    sealed interface CollectResult {
        data class Success(val item: MemoryItemEntity, val degraded: Boolean) : CollectResult
        data class Duplicate(val existing: MemoryItemEntity) : CollectResult
        data class Rejected(val reason: String) : CollectResult
    }

    suspend fun collect(memoryText: String, source: String, appId: String = CONSOLE_APP_ID): CollectResult {
        // ① 净化（sanitize）
        val text = memoryText.trim()
        if (text.isEmpty()) return CollectResult.Rejected("内容为空")
        if (text.length > MAX_CONTENT_LENGTH) return CollectResult.Rejected("内容过长（超过 $MAX_CONTENT_LENGTH 字符）")

        val now = System.currentTimeMillis()

        // ② 安全门控（确定性栅栏）
        val gateResult = gate.evaluate(text)

        // ③ LLM 结构化抽取（网络/解析失败 → 降级原文入库）
        // 注意：try/catch 双分支赋值同一个 val 会触发 "'val' cannot be reassigned"，用解构表达式
        val (extracted, degraded) = try {
            TextExtractor.extract(provider, text) to false
        } catch (e: Exception) {
            TextExtractor.degraded(text, source) to true
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
        val item = MemoryItemEntity(
            memoId = memoId,
            contentHash = hash,
            content = text,
            title = extracted.title,
            category = extracted.category,
            tags = extracted.tags.joinToString(","),
            source = source,
            appId = appId,
            policyLevel = if (sensitive) 2 else 1,
            createdAt = now,
            updatedAt = now,
            confidence = extracted.confidence.toFloat(),
            evidenceRaw = text
        )
        itemDao.insert(item)

        // ⑥ 双日志：传入 + 推理
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
                    "degraded" to degraded
                )
            )
        )
        logDao.insert(
            MemoryLogEntity(
                logType = LOG_INFER, action = "extract",
                appId = appId, memoIds = memoId, timestamp = now,
                source = source,
                contentSummary = "结构化抽取：${extracted.title}（${extracted.category}）",
                tags = extracted.tags.joinToString(","),
                extra = JsonTools.buildJson(
                    "model" to provider.name,
                    "confidence" to extracted.confidence,
                    "entities" to extracted.entities.joinToString(","),
                    "degraded" to degraded,
                    "llmSensitivity" to extracted.sensitivity
                )
            )
        )

        return CollectResult.Success(item, degraded)
    }
}
