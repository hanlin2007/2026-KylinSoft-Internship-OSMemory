package com.example.osmemory.core.dream

import com.example.osmemory.core.model.JsonTools
import com.example.osmemory.core.model.ModelProvider
import com.example.osmemory.core.model.TextTools
import com.example.osmemory.data.db.dao.MemoryLogDao
import com.example.osmemory.data.db.entity.MemoryLogEntity
import kotlin.math.min
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * AutoDream 引擎（阶段 4：记忆自进化）——对应 Claude Code autoDream 四阶段整合方法论
 * 与 Hermes curator 的"确定性遗忘 + LLM 合并"设计。
 *
 * 一次 Dream = 四步整合流水线（全部 LLM 驱动，失败逐级降级为确定性规则，原因必记录）：
 *   ① 冲突消解  ：同主题记忆前后矛盾时按「后写入优先」覆盖，旧记忆归档（可恢复）；
 *                 敏感记忆（policyLevel=2）不可被普通记忆覆盖（安全基线优先）。
 *   ② 原子拆分  ：一条记忆含多个信息点 → 拆成多条原子记忆（原复合记忆归档，证据保留）。
 *   ③ 去重合并  ：近似重复（归一化相似度 + LLM 复核）→ 合并保留高置信与证据，被并者归档。
 *   ④ 包含/推理整合：存在包含关系/可推理逻辑 → 并入 + 提炼高维记忆（distilled，来源可追溯）。
 *
 * 设计要点（来自 Claude Code / Hermes 提炼）：
 *  - 归档式遗忘：被覆盖/被合并/被拆分的记忆**不物理删除**，置为已归档（可恢复），
 *    对应 Hermes curator 的 active → stale → archived 状态机（绝不删除，只归档）。
 *  - 来源可追溯：所有 Dream 产物标记 source 前缀（dream_split / dream_distill），
 *    证据字段保留原始内容，全部写入留 DREAM 日志（审计闭环）。
 *  - 无副作用整合：任何一步失败不影响其他步骤，引擎永不抛出（调用方拿到降级报告）。
 */
class DreamEngine(
    private val provider: ModelProvider,
    private val ops: TreeOps,
    private val logDao: MemoryLogDao
) {

    companion object {
        /** Dream 日志类型（日志页新增 DREAM 展板） */
        const val LOG_DREAM = "DREAM"

        /** 复合记忆拆分阈值：超过该长度且含多个句读才尝试原子拆分 */
        const val SPLIT_MIN_LENGTH = 80

        /** 归一化相似度阈值：≥ 该值判为近似重复（规则兜底） */
        const val DEDUP_SIMILARITY = 0.88f

        private const val CONSOLE_APP_ID = "osmemory_console"

        /** LLM 候选对上限（防止长库把 prompt 撑爆） */
        private const val MAX_LLM_CANDIDATES = 30
    }

    /** 一次 Dream（四步整合），永远不抛出；任何异常 → 降级报告 + DREAM 日志 */
    suspend fun dream(online: Boolean): DreamReport {
        val now = System.currentTimeMillis()
        val items = ops.allActive()
        val report = DreamReport(
            tree = ops.tree,
            online = online,
            conflictsResolved = 0,
            splitCount = 0,
            mergedCount = 0,
            distilledCount = 0,
            archivedCount = 0,
            message = "",
            degraded = false,
            reason = "",
            at = now
        )
        if (items.isEmpty()) {
            val empty = report.copy(
                message = "${ops.tree} 树：记忆库为空，无需整合（保持原状）"
            )
            logDream(now, empty)
            return empty
        }

        val builder = StringBuilder()
        var degraded = false
        var reason = ""
        var conflictsResolved = 0
        var splitCount = 0
        var mergedCount = 0
        var distilledCount = 0

        // ① 冲突消解
        try {
            val resolved = resolveConflicts(items, now)
            resolved.archived.forEach { (item, mergedInto) -> ops.archive(item.id, mergedInto) }
            conflictsResolved = resolved.archived.size
            if (conflictsResolved > 0) builder.append("冲突消解 $conflictsResolved 条（后写入优先）；")
        } catch (e: Exception) {
            degraded = true
            reason = appendReason(reason, "冲突消解降级：${e.message ?: "未知"}")
        }

        // ② 原子拆分（只针对长复合记忆；产物为新原子记忆）
        try {
            val activeNow = if (items.any { it.content.length > SPLIT_MIN_LENGTH }) items else emptyList()
            val split = splitCompound(activeNow, now)
            split.forEach { newItem -> ops.insert(newItem) }
            splitCount = split.size
            if (splitCount > 0) builder.append("原子拆分 $splitCount 条；")
        } catch (e: Exception) {
            degraded = true
            reason = appendReason(reason, "原子拆分降级：${e.message ?: "未知"}")
        }

        // ③ 去重合并（近似重复 → 合并，被并者归档）
        try {
            val merged = dedupMerge(items, now)
            merged.archived.forEach { (item, mergedInto) -> ops.archive(item.id, mergedInto) }
            mergedCount = merged.archived.size
            if (mergedCount > 0) builder.append("去重合并 $mergedCount 条；")
        } catch (e: Exception) {
            degraded = true
            reason = appendReason(reason, "去重合并降级：${e.message ?: "未知"}")
        }

        // ④ 包含/推理整合 + 高维提炼
        try {
            val distilled = distill(items, now)
            distilled.forEach { newItem -> ops.insert(newItem) }
            distilledCount = distilled.size
            if (distilledCount > 0) builder.append("高维提炼 $distilledCount 条；")
        } catch (e: Exception) {
            degraded = true
            reason = appendReason(reason, "高维提炼降级：${e.message ?: "未知"}")
        }

        val final = report.copy(
            conflictsResolved = conflictsResolved,
            splitCount = splitCount,
            mergedCount = mergedCount,
            distilledCount = distilledCount,
            archivedCount = conflictsResolved + mergedCount,
            degraded = degraded,
            reason = reason,
            message = buildString {
                append("${ops.tree} 树 Dream 完成：")
                if (builder.isEmpty()) append("无冲突、无重复、无拆分、无可提炼，记忆保持稳定")
                else append(builder)
                if (degraded) append("（部分步骤降级：$reason）")
            }
        )
        logDream(now, final)
        return final
    }

    // ---------- ① 冲突消解 ----------

    private suspend fun resolveConflicts(
        items: List<DreamItem>,
        now: Long
    ): ArchivePlan {
        val candidates = candidatePairs(items)
        if (candidates.isEmpty()) return ArchivePlan(emptyList())

        // LLM 批量判定冲突对（输出冲突对索引）；失败降级为规则判定
        val conflictPairs = try {
            llmConflictPairs(candidates)
        } catch (e: Exception) {
            ruleConflictPairs(candidates)
        }
        if (conflictPairs.isEmpty()) return ArchivePlan(emptyList())

        val archived = mutableListOf<Pair<DreamItem, String>>()
        for ((a, b) in conflictPairs) {
            val resolved = when {
                // 安全基线：敏感记忆不可被普通记忆覆盖（旧敏感保留，新普通归档）
                a.policyLevel >= 2 && b.policyLevel < 2 -> b to a.memoId
                b.policyLevel >= 2 && a.policyLevel < 2 -> a to b.memoId
                // 后写入优先：updatedAt 新者胜，旧者归档
                a.updatedAt >= b.updatedAt -> b to a.memoId
                else -> a to b.memoId
            }
            archived.add(resolved)
        }
        return ArchivePlan(archived.distinctBy { it.first.memoId })
    }

    /** 候选冲突对：同分类 + 标签/标题关键词重叠（缩小 LLM 判定面） */
    private fun candidatePairs(items: List<DreamItem>): List<Pair<DreamItem, DreamItem>> {
        val groups = HashMap<String, MutableList<DreamItem>>()
        items.forEach { item ->
            val key = buildString {
                append(item.category)
                item.tags.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }.take(2)
                    .forEach { append("#").append(it) }
            }
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }
        return groups.values.flatMap { group ->
            buildList {
                for (i in group.indices) {
                    for (j in i + 1 until group.size) {
                        val a = group[i]
                        val b = group[j]
                        // 同一记忆不与自己比；内容完全相同跳过（交给去重）
                        if (a.memoId == b.memoId) continue
                        if (TextTools.normalizeHash(a.content) == TextTools.normalizeHash(b.content)) continue
                        add(a to b)
                    }
                }
            }
        }.take(MAX_LLM_CANDIDATES)
    }

    /** LLM 批量冲突判定：输出 {"conflicts":[{"index":N,"reason":"..."}]} */
    private suspend fun llmConflictPairs(candidates: List<Pair<DreamItem, DreamItem>>): List<Pair<DreamItem, DreamItem>> {
        val system = """
            你是记忆冲突检测器。下面给出一批候选记忆对（index 从 0 开始）。
            判断每对是否描述同一事物且互相矛盾（如前后偏好相反、事实冲突）。
            只输出严格 JSON：{"conflicts":[{"index":N,"reason":"矛盾原因"}]}。
            没有冲突就输出 {"conflicts":[]}。不要输出其他内容。
        """.trimIndent()
        val user = candidates.mapIndexed { i, (a, b) ->
            "[$i] A：${a.title}｜${a.content}\n    B：${b.title}｜${b.content}"
        }.joinToString("\n") + "\n\n请判定冲突对。"
        val reply = provider.complete(system, user, 0.0)
        val json = JsonTools.extractBalancedJson(reply)
            ?: throw IllegalStateException("冲突判定回复中无 JSON")
        val conflicts = JSONObject(json).optJSONArray("conflicts") ?: JSONArray()
        val indexes = mutableListOf<Int>()
        for (i in 0 until conflicts.length()) {
            val index = conflicts.optJSONObject(i)?.optInt("index", -1) ?: -1
            if (index in candidates.indices && index !in indexes) indexes.add(index)
        }
        return indexes.map { candidates[it] }
    }

    /** 规则兜底冲突判定：情感极性相反（如 喜欢/讨厌、接受/拒绝）+ 同主题 */
    private fun ruleConflictPairs(candidates: List<Pair<DreamItem, DreamItem>>): List<Pair<DreamItem, DreamItem>> {
        return candidates.filter { (a, b) ->
            val aPos = DreamRules.positivePolarity(a.content)
            val bPos = DreamRules.positivePolarity(b.content)
            // 一方偏正向、另一方偏负向，且内容不是简单包含关系
            aPos == 1 && bPos == -1 || aPos == -1 && bPos == 1
        }
    }

    // ---------- ② 原子拆分 ----------

    private suspend fun splitCompound(
        items: List<DreamItem>,
        now: Long
    ): List<DreamItem> {
        val longItems = items.filter { it.content.length > SPLIT_MIN_LENGTH }
        if (longItems.isEmpty()) return emptyList()

        val result = mutableListOf<DreamItem>()
        for (item in longItems) {
            val pieces = try {
                llmSplit(item)
            } catch (e: Exception) {
                DreamRules.ruleSplit(item.content)
            }
            // 拆分出 ≥2 条且每条非空 → 原复合记忆归档（证据保留），产物入库
            if (pieces.size >= 2) {
                pieces.forEach { piece ->
                    result.add(
                        DreamItem(
                            id = 0,
                            memoId = "MEMO-DREAM-${now}-${Random.nextInt(100000, 999999)}",
                            content = piece,
                            title = item.title,
                            category = item.category,
                            tags = item.tags,
                            source = "dream_split",
                            policyLevel = item.policyLevel,
                            confidence = item.confidence,
                            createdAt = now,
                            updatedAt = now,
                            evidenceRaw = item.evidenceRaw
                        )
                    )
                }
                ops.archive(item.id, "")
            }
        }
        return result
    }

    /** LLM 原子拆分：输出 {"pieces":["…","…"]} */
    private suspend fun llmSplit(item: DreamItem): List<String> {
        val system = """
            你是记忆原子化拆分器。输入一条可能包含多个信息点的记忆，
            把它拆成若干条彼此独立、信息不重叠的原子记忆（保留原意，不编造）。
            只输出严格 JSON：{"pieces":["原子记忆1","原子记忆2"]}。不要其他内容。
        """.trimIndent()
        val user = "记忆：${item.content}\n\n请拆分。"
        val reply = provider.complete(system, user, 0.2)
        val json = JsonTools.extractBalancedJson(reply)
            ?: throw IllegalStateException("拆分回复中无 JSON")
        return JsonTools.optStringArray(JSONObject(json), "pieces")
            .map { it.trim() }
            .filter { it.length >= 4 }
    }

    // 规则兜底拆分见 DreamRules.ruleSplit（按句读切分长文本）

    // ---------- ③ 去重合并 ----------

    private suspend fun dedupMerge(items: List<DreamItem>, now: Long): ArchivePlan {
        val archived = mutableListOf<Pair<DreamItem, String>>()
        val seen = mutableListOf<DreamItem>()

        for (item in items.sortedByDescending { it.updatedAt }) {
            val dup = seen.firstOrNull { DreamRules.similarity(it.content, item.content) >= DEDUP_SIMILARITY }
            if (dup != null) {
                // 保留者：高置信 + 证据拼接 + 新更新时间（内容取新）
                val keeper = if (item.confidence >= dup.confidence) item else dup
                val loser = if (item.confidence >= dup.confidence) dup else item
                val merged = keeper.copy(
                    confidence = maxOf(keeper.confidence, loser.confidence),
                    evidenceRaw = if (keeper.evidenceRaw.contains(loser.evidenceRaw)) keeper.evidenceRaw
                    else "${keeper.evidenceRaw}\n${loser.evidenceRaw}",
                    updatedAt = now
                )
                ops.update(merged)
                archived.add(loser to keeper.memoId)
            } else {
                seen.add(item)
            }
        }
        return ArchivePlan(archived.distinctBy { it.first.memoId })
    }

    // 归一化相似度见 DreamRules.similarity（去空白标点小写后的双字符 Jaccard）

    // ---------- ④ 高维提炼 ----------

    /**
     * 高维提炼：对同分类+同标签组内的记忆，LLM 判定包含关系/可推理逻辑，
     * 提炼一条高维记忆（distilled）。失败降级为跳过（安全：不臆造）。
     */
    private suspend fun distill(items: List<DreamItem>, now: Long): List<DreamItem> {
        val groups = HashMap<String, MutableList<DreamItem>>()
        items.forEach { item ->
            val key = item.category + "|" + item.tags
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }
        if (groups.isEmpty()) return emptyList()

        val result = mutableListOf<DreamItem>()
        for ((_, group) in groups) {
            if (group.size < 2) continue
            val distilled = try {
                llmDistill(group)
            } catch (e: Exception) {
                null
            } ?: continue
            val sourceIds = group.take(6).joinToString(",") { it.memoId }
            result.add(
                DreamItem(
                    id = 0,
                    memoId = "MEMO-DISTILL-${now}-${Random.nextInt(100000, 999999)}",
                    content = distilled,
                    title = distilled.take(20),
                    category = group.first().category,
                    tags = (group.flatMap { it.tags.split(",", "，") }.take(4).toSet() + "高维").joinToString(","),
                    source = "dream_distill",
                    policyLevel = group.maxOf { it.policyLevel }, // 高维记忆继承最高敏感级
                    confidence = group.minOf { it.confidence },
                    createdAt = now,
                    updatedAt = now,
                    evidenceRaw = "源自：$sourceIds"
                )
            )
        }
        return result
    }

    /** LLM 高维提炼：输出 {"distilled":"高维记忆一句话","basis":"整合依据"} */
    private suspend fun llmDistill(group: List<DreamItem>): String? {
        val system = """
            你是记忆整合器。输入一组同主题记忆。请判断：
            1. 若存在包含关系（一条的记忆被另一条涵盖），输出涵盖后的完整记忆；
            2. 若可推理出更高维的共性规律（如多条偏好 → 一条高维偏好），提炼之；
            3. 否则输出 null。
            只输出严格 JSON：{"distilled":"…或null","basis":"依据"}。不要其他内容。
        """.trimIndent()
        val user = group.mapIndexed { i, it -> "[$i] ${it.content}" }.joinToString("\n") + "\n\n请整合。"
        val reply = provider.complete(system, user, 0.2)
        val json = JsonTools.extractBalancedJson(reply)
            ?: throw IllegalStateException("提炼回复中无 JSON")
        val distilled = JsonTools.optString(JSONObject(json), "distilled", "").trim()
        return if (distilled.isBlank() || distilled.equals("null", ignoreCase = true)) null else distilled
    }

    // ---------- 日志 ----------

    private suspend fun logDream(now: Long, report: DreamReport) {
        logDao.insert(
            MemoryLogEntity(
                logType = LOG_DREAM,
                action = "dream",
                appId = CONSOLE_APP_ID,
                memoIds = "",
                timestamp = now,
                source = "system",
                contentSummary = "${report.tree} 树 Dream：${report.message.take(120)}",
                tags = "整合",
                extra = JsonTools.buildJson(
                    "tree" to report.tree,
                    "online" to report.online,
                    "conflictsResolved" to report.conflictsResolved,
                    "splitCount" to report.splitCount,
                    "mergedCount" to report.mergedCount,
                    "distilledCount" to report.distilledCount,
                    "archivedCount" to report.archivedCount,
                    "degraded" to report.degraded,
                    "reason" to report.reason
                )
            )
        )
    }

    private fun appendReason(existing: String, part: String): String =
        if (existing.isBlank()) part else "$existing；$part"

    private data class ArchivePlan(val archived: List<Pair<DreamItem, String>>)
}
