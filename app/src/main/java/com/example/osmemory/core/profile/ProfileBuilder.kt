package com.example.osmemory.core.profile

import com.example.osmemory.core.model.JsonTools
import com.example.osmemory.core.model.ModelProvider
import com.example.osmemory.core.model.TextTools
import com.example.osmemory.core.pipeline.MemoryPipeline.Companion.LOG_INFER
import com.example.osmemory.core.pipeline.MemoryPipeline.Companion.LOG_RETRIEVE
import com.example.osmemory.data.db.dao.MemoryItemDao
import com.example.osmemory.data.db.dao.MemoryLogDao
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryLogEntity
import org.json.JSONObject

/**
 * 记忆画像（阶段 2）：三板块（用户画像 / 风格偏好 / 工作项目）+ 遴选标签。
 *
 * 聚合范围：本地树（Local Tree，source of truth）。按分类归组：
 *  - 用户画像 ← 用户画像
 *  - 风格偏好 ← 偏好风格
 *  - 工作项目 ← 项目上下文 + 任务轨迹
 *
 * LLM 驱动生成（杜绝规则引擎）；离线/模型失败降级为统计式画像（最高频标签），
 * 降级原因写入 INFER 日志（可观测）。画像全过程留 RETRIEVE + INFER 日志。
 */
class ProfileBuilder(
    private val provider: ModelProvider,
    private val itemDao: MemoryItemDao,
    private val logDao: MemoryLogDao
) {

    data class ProfileResult(
        val userProfile: String,
        val stylePreference: String,
        val workProject: String,
        val tags: List<String>,
        /** ai=LLM 生成，fallback=离线/失败统计降级 */
        val source: String,
        val reason: String,
        val usedCount: Int,
        val at: Long
    ) {
        val degraded: Boolean get() = source == "fallback"
    }

    private val SYSTEM_PROMPT = """
        你是 OS Memory 系统的用户画像模块。以下是从本地记忆库按三个维度归组的记忆：
        维度一「用户画像」、维度二「风格偏好」、维度三「工作项目」。
        请为每个维度输出一段凝练的中文总结（≤80 字，基于给定记忆，不要编造），
        并给出 3~6 个跨维度的遴选标签。
        输出且只输出一个 JSON 对象：
        {
          "userProfile": "……",
          "stylePreference": "……",
          "workProject": "……",
          "tags": ["标签1", "标签2", "标签3"]
        }
    """.trimIndent()

    suspend fun build(appId: String = "osmemory_console"): ProfileResult {
        val now = System.currentTimeMillis()

        val profileItems = itemDao.byCategory("用户画像", 10)
        val styleItems = itemDao.byCategory("偏好风格", 10)
        val workItems = itemDao.byCategory("项目上下文", 10) +
            itemDao.byCategory("任务轨迹", 10)
        val used = (profileItems + styleItems + workItems).distinctBy { it.id }

        // 统计降级用：全库最高频标签
        val fallbackTags = topTags(used, 8)

        // 记忆太少 → 不浪费 LLM 调用，直接给可操作的提示
        if (used.size <= 5) {
            return buildLowCountProfile(now, used.size, fallbackTags, appId)
        }

        val aiResult = runCatching {
            provider.complete(SYSTEM_PROMPT, buildUserPrompt(profileItems, styleItems, workItems), 0.3)
        }

        return if (aiResult.isSuccess) {
            try {
                val parsed = parse(aiResult.getOrThrow())
                val tags = parsed.tags.ifEmpty { fallbackTags }
                // 若模型仍未给出任一维度，标注记忆过少建议
                val hint = if (parsed.userProfile.isBlank() || parsed.stylePreference.isBlank() || parsed.workProject.isBlank()) {
                    "（提示：当前记忆数量较少，部分维度分析有限。继续沉淀更多维度记忆后画像会更完整）"
                } else ""
                logProfile(now, used, degraded = false, reason = hint, tags = tags, appId = appId)
                ProfileResult(
                    userProfile = parsed.userProfile.ifBlank { "记忆已高维度整合，当前可聚合记忆较少，建议在「用户画像」相关维度继续沉淀更多日常信息。" },
                    stylePreference = parsed.stylePreference.ifBlank { "记忆已高维度整合，当前可聚合记忆较少，建议在「偏好风格」相关维度继续沉淀更多偏好记录。" },
                    workProject = parsed.workProject.ifBlank { "记忆已高维度整合，当前可聚合记忆较少，建议在「项目上下文」「任务轨迹」相关维度继续沉淀更多工作记录。" },
                    tags = tags,
                    source = "ai",
                    reason = hint,
                    usedCount = used.size,
                    at = now
                )
            } catch (e: Exception) {
                buildFallback(now, profileItems, styleItems, workItems, used, fallbackTags, e.message ?: "解析失败", appId)
            }
        } else {
            buildFallback(now, profileItems, styleItems, workItems, used, fallbackTags, aiResult.exceptionOrNull()?.message ?: "未知错误", appId)
        }
    }

    /** 记忆数量极低（≤5 条）：不调 LLM，直接返回可操作提示。 */
    private suspend fun buildLowCountProfile(
        now: Long,
        count: Int,
        fallbackTags: List<String>,
        appId: String
    ): ProfileResult {
        val hint = "记忆已高维度整合，当前记忆总数仅 $count 条，建议在各维度继续沉淀更多日常记忆（用户画像/偏好风格/项目上下文），积累至 10 条以上再生成画像。"
        val result = ProfileResult(
            userProfile = hint,
            stylePreference = "同上：记忆总数仅 $count 条，风格偏好维度暂无足够样本。",
            workProject = "同上：记忆总数仅 $count 条，工作项目维度暂无足够样本。",
            tags = fallbackTags.ifEmpty { listOf("记忆积攒中") },
            source = "fallback",
            reason = "记忆不足（$count 条，阈值 5）",
            usedCount = count,
            at = now
        )
        logProfile(now, emptyList(), degraded = true, reason = result.reason, tags = result.tags, appId = appId)
        return result
    }

    private suspend fun buildFallback(
        now: Long,
        profileItems: List<MemoryItemEntity>,
        styleItems: List<MemoryItemEntity>,
        workItems: List<MemoryItemEntity>,
        used: List<MemoryItemEntity>,
        fallbackTags: List<String>,
        reason: String,
        appId: String
    ): ProfileResult {
        val result = ProfileResult(
            userProfile = summarizeSection(profileItems),
            stylePreference = summarizeSection(styleItems),
            workProject = summarizeSection(workItems),
            tags = fallbackTags,
            source = "fallback",
            reason = reason,
            usedCount = used.size,
            at = now
        )
        logProfile(now, used, degraded = true, reason = reason, tags = fallbackTags, appId = appId)
        return result
    }

    /** 离线统计降级：一句话概括该维度（条数 + 高频标签） */
    private fun summarizeSection(items: List<MemoryItemEntity>): String {
        if (items.isEmpty()) return "暂无可聚合的记忆（该维度尚无沉淀）"
        val tags = topTags(items, 3)
        return "共沉淀 ${items.size} 条记忆，高频主题：${tags.joinToString("、")}"
    }

    /** 统计最高频标签（标签逗号分隔存储，按出现次数排序） */
    private fun topTags(items: List<MemoryItemEntity>, limit: Int): List<String> {
        val freq = LinkedHashMap<String, Int>()
        for (item in items) {
            item.tags.split(",", "，")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { freq[it] = (freq[it] ?: 0) + 1 }
        }
        return freq.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
    }

    private fun buildUserPrompt(
        profileItems: List<MemoryItemEntity>,
        styleItems: List<MemoryItemEntity>,
        workItems: List<MemoryItemEntity>
    ): String {
        fun block(title: String, items: List<MemoryItemEntity>) =
            "【$title】\n" + items.joinToString("\n") { "- ${TextTools.truncate(it.content, 50)}" }

        return listOf(
            block("用户画像", profileItems),
            block("风格偏好", styleItems),
            block("工作项目", workItems)
        ).joinToString("\n\n") + "\n\n请生成画像。"
    }

    private fun parse(rawReply: String): Parsed {
        val jsonText = JsonTools.extractBalancedJson(rawReply)
            ?: throw IllegalStateException("画像回复中未找到合法 JSON：${rawReply.take(150)}")
        val json = JSONObject(jsonText)
        return Parsed(
            userProfile = JsonTools.optString(json, "userProfile", "").trim(),
            stylePreference = JsonTools.optString(json, "stylePreference", "").trim(),
            workProject = JsonTools.optString(json, "workProject", "").trim(),
            tags = JsonTools.optStringArray(json, "tags")
        )
    }

    private suspend fun logProfile(
        now: Long,
        used: List<MemoryItemEntity>,
        degraded: Boolean,
        reason: String,
        tags: List<String>,
        appId: String
    ) {
        // RETRIEVE：画像遴选记忆的过程留痕
        logDao.insert(
            MemoryLogEntity(
                logType = LOG_RETRIEVE, action = "profile_select",
                appId = appId,
                memoIds = used.joinToString(",") { it.memoId },
                timestamp = now, source = "system",
                contentSummary = "画像遴选记忆 ${used.size} 条（用户画像/风格偏好/工作项目）",
                extra = JsonTools.buildJson("scope" to "local_tree")
            )
        )
        // INFER：画像生成（含降级原因）
        logDao.insert(
            MemoryLogEntity(
                logType = LOG_INFER, action = "profile",
                appId = appId,
                timestamp = now, source = "system",
                contentSummary = if (degraded) "画像生成：统计降级（$reason）"
                else "画像生成：三板块 + 遴选标签（${tags.size} 个）",
                tags = tags.joinToString(","),
                extra = JsonTools.buildJson(
                    "model" to provider.name,
                    "degraded" to degraded,
                    "reason" to reason
                )
            )
        )
    }

    private data class Parsed(
        val userProfile: String,
        val stylePreference: String,
        val workProject: String,
        val tags: List<String>
    )
}
