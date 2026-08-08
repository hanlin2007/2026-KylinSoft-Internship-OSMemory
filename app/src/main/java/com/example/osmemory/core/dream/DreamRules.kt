package com.example.osmemory.core.dream

/**
 * Dream 确定性规则（LLM 不可用时的降级兜底，纯函数、可单测）。
 *
 * 四个能力：
 *  - [positivePolarity]：情感极性检测（冲突消解规则兜底）；
 *  - [topicSimilarity]：剥离偏好/否定措辞后的主题相似度（冲突候选召回）；
 *  - [similarity]：归一化字符 bigram Jaccard 相似度（去重合并规则兜底）；
 *  - [ruleSplit]：按句读切分（原子拆分规则兜底）。
 */
object DreamRules {

    private const val POSITIVE = 1
    private const val NEGATIVE = -1
    private const val NEUTRAL = 0

    private val POSITIVE_WORDS = listOf(
        "喜欢", "热爱", "偏好", "接受", "可以", "愿意", "支持", "推荐", "赞成", "爱好"
    )
    private val NEGATIVE_WORDS = listOf(
        "不再喜欢", "不再支持", "不再接受", "不再愿意", "不喜欢", "不热爱", "不偏好",
        "不接受", "不可以", "不愿意", "不支持", "不推荐", "不赞成", "不爱", "讨厌了",
        "讨厌", "反感", "拒绝", "不能", "反对", "取消", "不再", "停止"
    )

    private val TOPIC_NOISE_WORDS = (POSITIVE_WORDS + NEGATIVE_WORDS + listOf(
        "我已经", "我现在", "我目前", "用户已经", "用户现在", "用户目前", "我", "用户"
    )).distinct().sortedByDescending { it.length }

    private val GENERIC_NEGATION = Regex("不(?!错|仅|但|锈|论)|没(?!错)|无(?!锡|论)")
    private val FACT_TOKEN = Regex(
        "(?i)(?:[+-]?\\d+(?:\\.\\d+)?\\s*(?:tb|gb|mb|kb|岁|点|元|次|分钟|小时|天|周|月|年|公斤|千克|斤|米|公里|个|杯|%)?" +
            "|[零一二三四五六七八九十百千万两]+\\s*(?:岁|点|元|次|分钟|小时|天|周|月|年|公斤|千克|斤|米|公里|个|杯))"
    )

    /**
     * 情感极性：正 / 负 / 中性（词表命中计分，多词累计）。
     *
     * 先移除较长的否定短语，再统计正向词，避免“不喜欢”同时命中“喜欢”而被误判为正向。
     */
    fun positivePolarity(text: String): Int {
        var score = 0
        var remaining = text
        NEGATIVE_WORDS.sortedByDescending { it.length }.forEach { word ->
            if (remaining.contains(word)) {
                score--
                remaining = remaining.replace(word, "")
            }
        }
        POSITIVE_WORDS.forEach { if (remaining.contains(it)) score++ }
        return if (score > 0) POSITIVE else if (score < 0) NEGATIVE else NEUTRAL
    }

    /** 是否包含明确否定/拒绝语义（用于“肯定事实 vs 否定事实”的规则冲突兜底）。 */
    fun isNegated(text: String): Boolean = NEGATIVE_WORDS.any(text::contains) ||
        GENERIC_NEGATION.containsMatchIn(text)

    /**
     * 近似文本是否可以安全进入重复合并。否定立场相反或数值事实不同的文本必须留给冲突判定，
     * 例如“喜欢/不喜欢”以及“4GB/8GB”不能因为其余长句相同而被误合并。
     */
    fun mergeCompatible(a: String, b: String): Boolean {
        if (isNegated(a) xor isNegated(b)) return false
        val aPolarity = positivePolarity(a)
        val bPolarity = positivePolarity(b)
        if (aPolarity == POSITIVE && bPolarity == NEGATIVE ||
            aPolarity == NEGATIVE && bPolarity == POSITIVE
        ) return false
        return factTokens(a) == factTokens(b)
    }

    /**
     * 主题相似度：先剥离“喜欢/不喜欢/支持/不支持”等立场词，再比较正文主题。
     * 例如“我喜欢跑步”与“我不喜欢跑步”的主题都归一为“跑步”，相似度为 1。
     */
    fun topicSimilarity(a: String, b: String): Float {
        val topicA = normalizeTopic(a)
        val topicB = normalizeTopic(b)
        if (topicA.isEmpty() || topicB.isEmpty()) return 0f
        if (topicA == topicB) return 1f
        if (minOf(topicA.length, topicB.length) >= 2 &&
            (topicA.contains(topicB) || topicB.contains(topicA))
        ) return minOf(topicA.length, topicB.length).toFloat() /
            maxOf(topicA.length, topicB.length).toFloat()
        return normalizedSimilarity(topicA, topicB)
    }

    /**
     * 归一化字符集相似度：去空白/标点/小写后，取双字符 n-gram 的 Jaccard。
     * 相同文本 = 1.0；近似重复（≤1 处措辞差异）通常 ≥ 0.88。
     */
    fun similarity(a: String, b: String): Float {
        val normA = normalize(a)
        val normB = normalize(b)
        return normalizedSimilarity(normA, normB)
    }

    /** 仅忽略大小写、空白和标点后正文完全一致，才允许执行无模型的确定性合并。 */
    fun exactEquivalent(a: String, b: String): Boolean {
        val normA = normalizeForExactMerge(a)
        val normB = normalizeForExactMerge(b)
        return normA.isNotEmpty() && normA == normB
    }

    private fun normalizedSimilarity(normA: String, normB: String): Float {
        if (normA.isEmpty() || normB.isEmpty()) return 0f
        if (normA == normB) return 1f
        val gramsA = bigrams(normA)
        val gramsB = bigrams(normB)
        if (gramsA.isEmpty() || gramsB.isEmpty()) return 0f
        val intersect = gramsA.intersect(gramsB).size
        val union = gramsA.union(gramsB).size
        return if (union == 0) 0f else intersect.toFloat() / union.toFloat()
    }

    /**
     * 规则兜底拆分：按中文/英文句读（。；；!?）切分。
     * 至少 2 段且每段 ≥ 4 字符才认为有效拆分，否则返回空（不拆分）。
     */
    fun ruleSplit(text: String): List<String> {
        val parts = text.split(Regex("[。；;！!？?]"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return if (parts.size >= 2 && parts.all { it.length >= 4 }) parts else emptyList()
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[\\s\\p{Punct}，。；：！？、\"'（）()【】《》]+"), "")

    /** 合并规范化必须保留小数点、正负号、路径分隔符等可能改变事实含义的正文标点。 */
    private fun normalizeForExactMerge(text: String): String = text.trim().lowercase()
        .replace(Regex("\\s+"), "")
        .replace(Regex("[。！？.!?]+$"), "")

    private fun normalizeTopic(text: String): String {
        var normalized = normalize(text)
        TOPIC_NOISE_WORDS.forEach { normalized = normalized.replace(it, "") }
        return normalized.replace(GENERIC_NEGATION, "")
    }

    /** 保留事实值出现顺序与次数，避免“4GB/128GB”字段互换后仍被当作同一集合。 */
    private fun factTokens(text: String): List<String> = FACT_TOKEN.findAll(text.lowercase())
        .map { it.value.replace(Regex("\\s+"), "") }
        .toList()

    private fun bigrams(text: String): Set<String> =
        buildSet {
            for (i in 0 until text.length - 1) add(text.substring(i, i + 2))
        }
}
