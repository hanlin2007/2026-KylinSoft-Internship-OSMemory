package com.example.osmemory.core.dream

import com.example.osmemory.core.model.JsonTools
import com.example.osmemory.core.model.ModelProvider
import com.example.osmemory.core.model.TextTools
import com.example.osmemory.data.db.dao.MemoryLogDao
import com.example.osmemory.data.db.entity.MemoryLogEntity
import kotlinx.coroutines.CancellationException
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
 *   ③ 去重合并  ：规范化后正文等价 → 合并保留高置信与证据，被并者归档。
 *   ④ 包含/推理整合：存在包含关系/可推理逻辑 → 并入 + 提炼高维记忆（distilled，来源可追溯）。
 *
 * 设计要点（来自 Claude Code / Hermes 提炼）：
 *  - 归档式遗忘：被覆盖/被合并/被拆分的记忆**不物理删除**，置为已归档（可恢复），
 *    对应 Hermes curator 的 active → stale → archived 状态机（绝不删除，只归档）。
 *  - 来源可追溯：所有 Dream 产物标记 source 前缀（dream_split / dream_distill），
 *    证据字段保留原始内容，全部写入留 DREAM 日志（审计闭环）。
 *  - 分步降级：模型或单阶段失败不影响其他步骤；协程取消仍向上传播，避免取消后继续写库。
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

        /** 确定性合并要求规范化正文完全等价；避免数值/地址等高相似事实被误归档。 */
        const val DEDUP_SIMILARITY = 1.0f

        /** 剥离立场词后达到该相似度，才允许规则直接判定为同主题冲突。 */
        private const val CONFLICT_TOPIC_SIMILARITY = 0.72f

        /** 冲突候选召回阈值；候选仍需规则或 LLM 二次判定。 */
        private const val CANDIDATE_TOPIC_SIMILARITY = 0.30f

        private const val CONSOLE_APP_ID = "osmemory_console"

        /** LLM 候选对上限（防止长库把 prompt 撑爆） */
        private const val MAX_LLM_CANDIDATES = 30
    }

    /**
     * 一次 Dream（四步整合）。[useModel] 为 false 时不触发模型加载，只执行确定性规则；
     * 这让低内存/模型未就绪设备仍可完成重复合并和常见偏好冲突消解。
     */
    suspend fun dream(
        online: Boolean,
        useModel: Boolean = true,
        fallbackReason: String = ""
    ): DreamReport {
        val now = System.currentTimeMillis()
        var activeItems = ops.allActive()
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
        if (activeItems.isEmpty()) {
            val empty = report.copy(
                message = "${ops.tree} 树：记忆库为空，无需整合（保持原状）"
            )
            logDream(now, empty)
            return empty
        }

        val builder = StringBuilder()
        val reasons = mutableListOf<String>()
        val details = mutableListOf<String>()
        val affectedMemoIds = linkedSetOf<String>()
        if (!useModel && fallbackReason.isNotBlank()) {
            reasons += "模型未参与：$fallbackReason，已使用确定性规则"
        }
        var conflictsResolved = 0
        var splitCount = 0
        var splitArchivedCount = 0
        var mergedCount = 0
        var distilledCount = 0
        var reviewedDuplicateKeys = emptySet<String>()

        // ① 冲突消解
        try {
            val resolved = resolveConflicts(activeItems, useModel)
            if (resolved.degradedReason.isNotBlank()) reasons += resolved.degradedReason
            reviewedDuplicateKeys = resolved.reviewedDuplicates.mapTo(hashSetOf(), ::pairKey)
            val itemByMemoId = activeItems.associateBy { it.memoId }
            resolved.archived.forEach { (item, mergedInto) ->
                ops.archive(item, mergedInto)
                val keeper = itemByMemoId[mergedInto]
                details += "冲突：${describe(item)} → 保留 ${describe(keeper, mergedInto)}"
                affectedMemoIds += item.memoId
                affectedMemoIds += mergedInto
            }
            conflictsResolved = resolved.archived.size
            if (conflictsResolved > 0) {
                builder.append("冲突消解 $conflictsResolved 条（安全等级优先，同级后写入优先）；")
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            reasons += "冲突消解降级：${e.message ?: "未知"}"
        }

        // 每一步只处理当前仍活跃的记忆，避免已归档项再次被合并或参与提炼。
        activeItems = ops.allActive()

        // ② 原子拆分（只针对长复合记忆；产物为新原子记忆）
        try {
            val split = splitCompound(activeItems, now, useModel)
            if (split.degradedReason.isNotBlank()) reasons += split.degradedReason
            split.groups.forEach { group ->
                group.created.forEach { newItem ->
                    ops.insert(newItem)
                    affectedMemoIds += newItem.memoId
                }
                ops.archive(group.original, "")
                affectedMemoIds += group.original.memoId
                splitCount += group.created.size
                splitArchivedCount++
                details += "拆分：${describe(group.original)} → ${group.created.size} 条原子记忆"
            }
            if (splitCount > 0) builder.append("原子拆分 $splitCount 条；")
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            reasons += "原子拆分降级：${e.message ?: "未知"}"
        }

        activeItems = ops.allActive()

        // ③ 去重合并（安全规范化后正文等价 → 合并，被并者归档）
        try {
            val merged = dedupMerge(activeItems, now, reviewedDuplicateKeys)
            merged.updated.forEach { ops.update(it) }
            val itemByMemoId = activeItems.associateBy { it.memoId }
            merged.archived.forEach { (item, mergedInto) ->
                ops.archive(item, mergedInto)
                val keeper = itemByMemoId[mergedInto]
                details += "合并：${describe(item)} → 保留 ${describe(keeper, mergedInto)}"
                affectedMemoIds += item.memoId
                affectedMemoIds += mergedInto
            }
            mergedCount = merged.archived.size
            if (mergedCount > 0) builder.append("去重合并 $mergedCount 条；")
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            reasons += "去重合并降级：${e.message ?: "未知"}"
        }

        activeItems = ops.allActive()

        // ④ 包含/推理整合 + 高维提炼
        try {
            val distilledHistory = ops.allArchived().filter { it.source == "dream_distill" }
            val distilled = distill(activeItems, distilledHistory, now, useModel)
            if (distilled.degradedReason.isNotBlank()) reasons += distilled.degradedReason
            distilled.created.forEach { newItem ->
                ops.insert(newItem)
                affectedMemoIds += newItem.memoId
                details += "提炼：生成 ${describe(newItem)}"
            }
            distilledCount = distilled.created.size
            if (distilledCount > 0) builder.append("高维提炼 $distilledCount 条；")
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            reasons += "高维提炼降级：${e.message ?: "未知"}"
        }

        val reason = reasons.distinct().joinToString("；")
        val degraded = reason.isNotBlank()
        val final = report.copy(
            conflictsResolved = conflictsResolved,
            splitCount = splitCount,
            mergedCount = mergedCount,
            distilledCount = distilledCount,
            archivedCount = conflictsResolved + splitArchivedCount + mergedCount,
            degraded = degraded,
            reason = reason,
            details = details,
            affectedMemoIds = affectedMemoIds.toList(),
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
        useModel: Boolean
    ): ArchivePlan {
        val candidates = candidatePairs(items)
        if (candidates.isEmpty()) return ArchivePlan(emptyList())

        // 明显的偏好/否定冲突优先走规则，剩余候选才交给 LLM，保证演示可重复且减少推理耗时。
        val rulePairs = ruleConflictPairs(candidates)
        val ruleKeys = rulePairs.mapTo(hashSetOf(), ::pairKey)
        val unresolved = candidates.filter { pairKey(it) !in ruleKeys }
        var degradedReason = ""
        val classification = if (!useModel || unresolved.isEmpty()) {
            PairClassification()
        } else {
            try {
                // 规则结果不受 prompt 容量限制；只截断真正需要模型复核的剩余候选。
                llmClassifyPairs(unresolved.take(MAX_LLM_CANDIDATES))
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                degradedReason = "记忆关系模型判定失败，已保留规则结果：${e.message ?: "未知"}"
                PairClassification()
            }
        }
        val conflictPairs = (rulePairs + classification.conflicts).distinctBy(::pairKey)
        val conflictKeys = conflictPairs.mapTo(hashSetOf(), ::pairKey)
        val reviewedDuplicates = classification.duplicates
            .filter { pairKey(it) !in conflictKeys }
            .filter { (a, b) -> DreamRules.mergeCompatible(a.content, b.content) }
            .distinctBy(::pairKey)

        return ArchivePlan(
            archived = if (conflictPairs.isEmpty()) emptyList() else conflictArchives(conflictPairs),
            reviewedDuplicates = reviewedDuplicates,
            degradedReason = degradedReason
        )
    }

    /**
     * 候选冲突对：标签按集合比较（不依赖顺序），并结合分类、标题和剥离立场词后的主题相似度。
     * 旧实现要求“分类 + 前两个标签字符串”完全一致，真实抽取只要标签换序就永远无法进入判定。
     */
    private fun candidatePairs(items: List<DreamItem>): List<Pair<DreamItem, DreamItem>> {
        val scored = mutableListOf<ScoredPair>()
        for (i in items.indices) {
            for (j in i + 1 until items.size) {
                val a = items[i]
                val b = items[j]
                if (a.memoId == b.memoId) continue

                val contentSimilarity = DreamRules.similarity(a.content, b.content)
                val mergeCompatible = DreamRules.mergeCompatible(a.content, b.content)
                // 只有规范化正文完全等价才直接交给确定性去重；高相似事实仍需冲突判定。
                if (DreamRules.exactEquivalent(a.content, b.content)) continue

                val topicSimilarity = DreamRules.topicSimilarity(a.content, b.content)
                val titleSimilarity = DreamRules.similarity(a.title, b.title)
                val sharedTags = tagSet(a.tags).intersect(tagSet(b.tags)).isNotEmpty()
                val sameCategory = a.category.isNotBlank() && a.category == b.category
                val eligible = topicSimilarity >= CANDIDATE_TOPIC_SIMILARITY ||
                    (sharedTags && (sameCategory || contentSimilarity >= 0.15f || titleSimilarity >= 0.15f)) ||
                    (sameCategory && maxOf(contentSimilarity, titleSimilarity) >= 0.25f)
                if (eligible) {
                    val score = topicSimilarity * 3f + contentSimilarity + titleSimilarity +
                        (if (sharedTags) 1f else 0f) + (if (sameCategory) 0.5f else 0f) +
                        (if (!mergeCompatible) 4f else 0f)
                    scored += ScoredPair(score, a to b)
                }
            }
        }
        return scored.sortedByDescending { it.score }.map { it.pair }
    }

    /** LLM 一次完成冲突与语义重复复核，避免同一批候选重复推理。 */
    private suspend fun llmClassifyPairs(
        candidates: List<Pair<DreamItem, DreamItem>>
    ): PairClassification {
        val system = """
            你是记忆关系检测器。下面给出一批候选记忆对（index 从 0 开始）。
            对每对判断：
            1. conflicts：描述同一事物但互相矛盾（偏好相反、数值或事实冲突）；
            2. duplicates：事实与立场完全相同，只是同义改写、数字写法或措辞不同；
            3. 两者都不是。冲突绝不能同时标为重复，字段和值的对应关系必须一致。
            只输出严格 JSON：{"conflicts":[{"index":N,"reason":"原因"}],
            "duplicates":[{"index":N,"reason":"原因"}]}。不要输出其他内容。
        """.trimIndent()
        val user = candidates.mapIndexed { i, (a, b) ->
            "[$i] A：${a.title}｜${a.content}\n    B：${b.title}｜${b.content}"
        }.joinToString("\n") + "\n\n请判定每对关系，并同时返回 conflicts 与 duplicates。"
        val reply = provider.complete(system, user, 0.0)
        val json = JsonTools.extractBalancedJson(reply)
            ?: throw IllegalStateException("冲突判定回复中无 JSON")
        val root = JSONObject(json)
        fun pairsFor(key: String): List<Pair<DreamItem, DreamItem>> {
            val array = root.optJSONArray(key) ?: JSONArray()
            val indexes = mutableListOf<Int>()
            for (i in 0 until array.length()) {
                val index = array.optJSONObject(i)?.optInt("index", -1) ?: -1
                if (index in candidates.indices && index !in indexes) indexes.add(index)
            }
            return indexes.map { candidates[it] }
        }
        return PairClassification(
            conflicts = pairsFor("conflicts"),
            duplicates = pairsFor("duplicates")
        )
    }

    /** 规则兜底冲突判定：相反立场或明确否定发生在同一主题上。 */
    private fun ruleConflictPairs(candidates: List<Pair<DreamItem, DreamItem>>): List<Pair<DreamItem, DreamItem>> {
        return candidates.filter { (a, b) ->
            val aPos = DreamRules.positivePolarity(a.content)
            val bPos = DreamRules.positivePolarity(b.content)
            val oppositePolarity = aPos == 1 && bPos == -1 || aPos == -1 && bPos == 1
            val explicitNegation = DreamRules.isNegated(a.content) xor DreamRules.isNegated(b.content)
            DreamRules.topicSimilarity(a.content, b.content) >= CONFLICT_TOPIC_SIMILARITY &&
                (oppositePolarity || explicitNegation)
        }
    }

    /**
     * 对每个直接冲突对独立决胜；一个记忆只要输掉任一直接冲突就归档，并把目标沿赢家链
     * 解析到最终活跃项。冲突关系不具传递性，因此不会把同一连通分量中的非冲突端点一并归档。
     */
    private fun conflictArchives(
        pairs: List<Pair<DreamItem, DreamItem>>
    ): List<Pair<DreamItem, String>> {
        val byMemoId = pairs.flatMap { listOf(it.first, it.second) }.associateBy { it.memoId }
        val priority = compareBy<DreamItem> { it.policyLevel }
            .thenBy { it.updatedAt }
            .thenBy { it.confidence }
            .thenBy { it.id }
        val winnersByLoser = mutableMapOf<String, MutableList<DreamItem>>()
        pairs.forEach { (a, b) ->
            val keeper = if (priority.compare(a, b) >= 0) a else b
            val loser = if (keeper.memoId == a.memoId) b else a
            winnersByLoser.getOrPut(loser.memoId) { mutableListOf() } += keeper
        }

        fun finalWinner(start: DreamItem): DreamItem {
            var current = start
            val visited = hashSetOf<String>()
            while (visited.add(current.memoId)) {
                val next = winnersByLoser[current.memoId]?.maxWithOrNull(priority) ?: return current
                current = next
            }
            return current
        }

        return winnersByLoser.mapNotNull { (loserId, directWinners) ->
            val loser = byMemoId[loserId] ?: return@mapNotNull null
            val winner = finalWinner(directWinners.maxWithOrNull(priority) ?: return@mapNotNull null)
            loser to winner.memoId
        }
    }

    // ---------- ② 原子拆分 ----------

    private suspend fun splitCompound(
        items: List<DreamItem>,
        now: Long,
        useModel: Boolean
    ): SplitPlan {
        val longItems = items.filter { it.content.length > SPLIT_MIN_LENGTH }
        if (longItems.isEmpty()) return SplitPlan(emptyList())

        val groups = mutableListOf<SplitGroup>()
        val warnings = mutableListOf<String>()
        for (item in longItems) {
            val pieces = if (!useModel) {
                DreamRules.ruleSplit(item.content)
            } else {
                try {
                    llmSplit(item)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    warnings += "拆分模型失败，已使用句读规则：${e.message ?: "未知"}"
                    DreamRules.ruleSplit(item.content)
                }
            }
            // 拆分出 ≥2 条且每条非空 → 原复合记忆归档（证据保留），产物入库
            if (pieces.size >= 2) {
                val created = pieces.map { piece ->
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
                }
                groups += SplitGroup(item, created)
            }
        }
        return SplitPlan(
            groups = groups,
            degradedReason = warnings.distinct().joinToString("；")
        )
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

    private fun dedupMerge(
        items: List<DreamItem>,
        now: Long,
        reviewedDuplicateKeys: Set<String>
    ): MergePlan {
        val archived = mutableListOf<Pair<DreamItem, String>>()
        val updated = mutableListOf<DreamItem>()
        val remaining = items.toMutableList()

        // 按相似关系的连通分量聚类，保证 3 条以上重复也只留下一个 keeper。
        while (remaining.isNotEmpty()) {
            val cluster = mutableListOf(remaining.removeAt(0))
            var expanded: Boolean
            do {
                val matches = remaining.filter { candidate ->
                    cluster.any { member ->
                        DreamRules.exactEquivalent(member.content, candidate.content) ||
                            (pairKey(member to candidate) in reviewedDuplicateKeys &&
                                DreamRules.mergeCompatible(member.content, candidate.content))
                    }
                }
                expanded = matches.isNotEmpty()
                cluster += matches
                remaining.removeAll(matches.toSet())
            } while (expanded)

            if (cluster.size < 2) continue
            val keeper = cluster.maxWithOrNull(
                compareBy<DreamItem> { it.policyLevel }
                    .thenBy { it.confidence }
                    .thenBy { it.updatedAt }
                    .thenBy { it.id }
            ) ?: continue
            val evidence = cluster.flatMap { item ->
                listOf(item.evidenceRaw, item.content)
            }.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString("\n")
            updated += keeper.copy(
                confidence = cluster.maxOf { it.confidence },
                evidenceRaw = evidence,
                updatedAt = now
            )
            cluster.filter { it.memoId != keeper.memoId }
                .forEach { archived += it to keeper.memoId }
        }
        return MergePlan(
            updated = updated,
            archived = archived.distinctBy { it.first.memoId }
        )
    }

    // 归一化相似度见 DreamRules.similarity（去空白标点小写后的双字符 Jaccard）

    // ---------- ④ 高维提炼 ----------

    /**
     * 高维提炼：对同分类+同标签组内的记忆，LLM 判定包含关系/可推理逻辑，
     * 提炼一条高维记忆（distilled）。失败降级为跳过（安全：不臆造）。
     */
    private suspend fun distill(
        items: List<DreamItem>,
        distilledHistory: List<DreamItem>,
        now: Long,
        useModel: Boolean
    ): DistillPlan {
        if (!useModel) return DistillPlan(emptyList())
        val existingFingerprints = (items.asSequence() + distilledHistory.asSequence())
            .filter { it.source == "dream_distill" }
            .map { it.evidenceRaw }
            .filter { it.startsWith("源自：") }
            .toMutableSet()
        val groups = HashMap<String, MutableList<DreamItem>>()
        // 已提炼项不能再次充当原料，否则每轮都会改变分组并生成 D1/D2/...。
        items.filterNot { it.source == "dream_distill" }.forEach { item ->
            val key = item.category + "|" + item.tags
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }
        if (groups.isEmpty()) return DistillPlan(emptyList())

        val result = mutableListOf<DreamItem>()
        val warnings = mutableListOf<String>()
        for ((_, group) in groups) {
            if (group.size < 2) continue
            val allSources = group.sortedBy { it.memoId }
            val sources = group.sortedWith(
                compareByDescending<DreamItem> { it.updatedAt }.thenByDescending { it.memoId }
            ).take(6)
            val sourceSignature = allSources.joinToString(",") {
                "${it.memoId}@${TextTools.normalizeHash(it.content).take(12)}"
            }
            val fingerprint = "源自：$sourceSignature"
            if (fingerprint in existingFingerprints) continue
            val distilled = try {
                llmDistill(sources)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                warnings += "高维提炼模型失败，已安全跳过：${e.message ?: "未知"}"
                null
            } ?: continue
            result.add(
                DreamItem(
                    id = 0,
                    memoId = "MEMO-DISTILL-${now}-${Random.nextInt(100000, 999999)}",
                    content = distilled,
                    title = distilled.take(20),
                    category = sources.first().category,
                    tags = (sources.flatMap { it.tags.split(",", "，") }.take(4).toSet() + "高维").joinToString(","),
                    source = "dream_distill",
                    policyLevel = sources.maxOf { it.policyLevel }, // 高维记忆继承最高敏感级
                    confidence = sources.minOf { it.confidence },
                    createdAt = now,
                    updatedAt = now,
                    evidenceRaw = fingerprint
                )
            )
            existingFingerprints += fingerprint
        }
        return DistillPlan(
            created = result,
            degradedReason = warnings.distinct().joinToString("；")
        )
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
        try {
            val visibleDetail = report.details.firstOrNull()?.let { "；$it" }.orEmpty()
            logDao.insert(
                MemoryLogEntity(
                    logType = LOG_DREAM,
                    action = "dream",
                    appId = CONSOLE_APP_ID,
                    memoIds = report.affectedMemoIds.joinToString(","),
                    timestamp = now,
                    source = "system",
                    contentSummary = ("${report.tree} 树 Dream：${report.message}$visibleDetail").take(220),
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
                        "reason" to report.reason,
                        "details" to report.details.joinToString("\n")
                    )
                )
            )
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            // 日志写入失败不能反向破坏已经完成的整合；调用方仍会收到完整 DreamReport。
        }
    }

    private fun describe(item: DreamItem?, fallbackMemoId: String = ""): String {
        if (item == null) return fallbackMemoId.ifBlank { "未知记忆" }
        val shortId = item.memoId.takeLast(6)
        return "「${TextTools.truncate(item.content, 24)}」($shortId)"
    }

    private fun tagSet(raw: String): Set<String> = raw.split(",", "，")
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .toSet()

    private fun pairKey(pair: Pair<DreamItem, DreamItem>): String =
        listOf(pair.first.memoId, pair.second.memoId).sorted().joinToString("|")

    private fun Exception.rethrowIfCancellation() {
        if (this is CancellationException) throw this
    }

    private data class ScoredPair(
        val score: Float,
        val pair: Pair<DreamItem, DreamItem>
    )

    private data class ArchivePlan(
        val archived: List<Pair<DreamItem, String>>,
        val reviewedDuplicates: List<Pair<DreamItem, DreamItem>> = emptyList(),
        val degradedReason: String = ""
    )

    private data class PairClassification(
        val conflicts: List<Pair<DreamItem, DreamItem>> = emptyList(),
        val duplicates: List<Pair<DreamItem, DreamItem>> = emptyList()
    )

    private data class SplitGroup(
        val original: DreamItem,
        val created: List<DreamItem>
    )

    private data class SplitPlan(
        val groups: List<SplitGroup>,
        val degradedReason: String = ""
    )

    private data class MergePlan(
        val updated: List<DreamItem>,
        val archived: List<Pair<DreamItem, String>>
    )

    private data class DistillPlan(
        val created: List<DreamItem>,
        val degradedReason: String = ""
    )
}
