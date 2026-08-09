package com.example.osmemory.core.retrieval

import com.example.osmemory.core.model.JsonTools
import com.example.osmemory.core.model.ModelProvider
import com.example.osmemory.core.model.TextTools
import com.example.osmemory.data.db.entity.MemoryItemEntity
import org.json.JSONObject

/**
 * LLM 语义重排（阶段 2 语义检索：关键词召回 + LLM 重排，落地 get_memo）
 *
 * 输入：查询词 + 关键词召回的候选记忆列表。
 * 输出：按语义相关性排序的列表（[RerankResult]）。
 *
 * 降级策略：模型不可用/离线/解析失败时返回原顺序（关键词顺序），并携带降级原因，
 * 由 MemoryRetriever 写入 RETRIEVE 日志（检索全程可审计）。
 */
class SemanticReranker(private val provider: ModelProvider) {

    data class RerankResult(
        val items: List<MemoryItemEntity>,
        val degraded: Boolean,
        val reason: String = ""
    )

    companion object {
        /** 参与重排的候选上限（控制 prompt 体量与耗时） */
        const val MAX_CANDIDATES = 15
    }

    private val SYSTEM_PROMPT = """
        你是 OS Memory 系统的检索重排模块。用户给出一条查询词，以及一批候选记忆。
        请判断每条候选与查询的语义相关性，输出且只输出一个 JSON 数组，
        数组元素为候选记忆的 memoId，按相关性从高到低排列。
        规则：只输出候选列表中出现过的 memoId，不要编造；数量不要超过候选总数。
        示例输出：["MEMO-...", "MEMO-..."]
    """.trimIndent()

    suspend fun rerank(query: String, candidates: List<MemoryItemEntity>): RerankResult {
        if (candidates.size <= 1) return RerankResult(candidates, degraded = false)
        val pool = candidates.take(MAX_CANDIDATES)
        val byId = pool.associateBy { it.memoId }

        val lines = pool.mapIndexed { i, item ->
            "${i + 1}. ${item.memoId}\n   标题：${item.title}\n   内容：${TextTools.truncate(item.content, 60)}"
        }.joinToString("\n")

        val user = "查询：$query\n\n候选记忆：\n$lines"

        return try {
            val reply = provider.complete(SYSTEM_PROMPT, user, 0.1)
            val orderedIds = parseOrderedIds(reply, byId.keys)
            val ordered = orderedIds.mapNotNull { byId[it] }
            // 未出现在回复中的候选追加到末尾（保持稳定）
            val orderedSet = ordered.mapTo(mutableSetOf()) { it.memoId }
            val tail = pool.filter { it.memoId !in orderedSet }
            RerankResult(ordered + tail, degraded = false)
        } catch (e: Exception) {
            RerankResult(candidates, degraded = true, reason = e.message ?: e.javaClass.simpleName)
        }
    }

    /** 解析模型回复中的 memoId 有序数组；非法/缺失时回退原顺序 */
    private fun parseOrderedIds(rawReply: String, known: Set<String>): List<String> {
        // 支持三种形态：{...} 对象 / 裸数组 [...] / {"ids":[...]}
        val asArray = JsonTools.extractBalancedArray(rawReply)?.let { arrText ->
            try {
                val arr = JSONObject("{\"a\": $arrText}").optJSONArray("a")
                (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.trim() }
            } catch (_: Exception) { null }
        }
        if (asArray != null && asArray.isNotEmpty()) {
            val ids = asArray.filter { it in known }.distinct()
            return if (ids.isNotEmpty()) ids else known.toList()
        }

        val jsonText = JsonTools.extractBalancedJson(rawReply) ?: return known.toList()
        val arr = JSONObject("{\"ids\": $jsonText}").optJSONArray("ids")
            ?: JSONObject(jsonText).optJSONArray("result")
            ?: return known.toList()
        val ids = (0 until arr.length())
            .mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } }
            .filter { it in known }
        return if (ids.isEmpty()) known.toList() else ids.distinct()
    }
}
