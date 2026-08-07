package com.example.osmemory.core.retrieval

import com.example.osmemory.core.model.JsonTools
import com.example.osmemory.core.model.TextTools
import com.example.osmemory.data.db.dao.MemoryItemDao
import com.example.osmemory.data.db.dao.MemoryLogDao
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryLogEntity
import com.example.osmemory.core.pipeline.MemoryPipeline.Companion.LOG_RETRIEVE

/**
 * 记忆检索（对应 PPT get_memo / Retrieval + Context Compiler）
 *
 * 流程：查询词切分 → 逐词关键词召回 → 权限过滤（policyLevel ≤ 应用权限）→ 去重合并
 *       → LLM 语义重排（阶段 2，离线/失败降级为关键词顺序）→ 命中自增 + 检索日志。
 *
 * 阶段 4 升级：应用读取敏感记忆前触发用户确认弹窗。
 */
class MemoryRetriever(
    private val itemDao: MemoryItemDao,
    private val logDao: MemoryLogDao,
    private val reranker: SemanticReranker? = null
) {

    /**
     * @param appId     调用方应用（已登记）
     * @param query     查询内容
     * @param limit     返回上限
     * @param policyMax 允许输出的最高敏感等级（普通应用=1，控制台=2）
     * @param semantic  是否执行 LLM 语义重排（阶段 2；控制台检索开启，离线自动降级）
     */
    suspend fun retrieve(
        appId: String,
        query: String,
        limit: Int = 10,
        policyMax: Int = 1,
        semantic: Boolean = false
    ): List<MemoryItemEntity> {
        val now = System.currentTimeMillis()
        val tokens = TextTools.tokenize(query)

        // 关键词召回 + 权限过滤 + 合并去重
        val merged = LinkedHashMap<Long, MemoryItemEntity>()
        for (token in tokens) {
            for (item in itemDao.keywordSearch(token, 50)) {
                if (item.policyLevel > policyMax) continue // 权限过滤
                merged[item.id] = item
                if (merged.size >= limit * 3) break
            }
            if (merged.size >= limit * 3) break
        }
        var result = merged.values.take(limit * 3).toList()

        // LLM 语义重排（阶段 2）
        var rerankDegraded = false
        var rerankReason = ""
        if (semantic && reranker != null) {
            val reranked = reranker.rerank(query, result)
            result = reranked.items.take(limit)
            rerankDegraded = reranked.degraded
            rerankReason = reranked.reason
        } else {
            result = result.take(limit)
        }

        // 命中自增（复用频率 / 质量字段）
        result.forEach { itemDao.bumpReuseCount(it.id) }

        // 检索日志（对应日志"检索"板块，含重排状态）
        logDao.insert(
            MemoryLogEntity(
                logType = LOG_RETRIEVE,
                action = "get_memo",
                appId = appId,
                memoIds = result.joinToString(",") { it.memoId },
                timestamp = now,
                source = "system",
                contentSummary = "查询“${TextTools.truncate(query, 30)}”→ 命中 ${result.size} 条" +
                    if (rerankDegraded) "（语义重排降级）" else "",
                extra = JsonTools.buildJson(
                    "tokens" to tokens.joinToString(","),
                    "policyMax" to policyMax,
                    "limit" to limit,
                    "semantic" to semantic,
                    "rerankDegraded" to rerankDegraded,
                    "rerankReason" to rerankReason
                )
            )
        )

        return result
    }
}
