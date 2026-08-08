package com.example.osmemory.core.dream

/**
 * Dream 确定性规则（LLM 不可用时的降级兜底，纯函数、可单测）。
 *
 * 三个能力：
 *  - [positivePolarity]：情感极性检测（冲突消解规则兜底）；
 *  - [similarity]：归一化字符 bigram Jaccard 相似度（去重合并规则兜底）；
 *  - [ruleSplit]：按句读切分（原子拆分规则兜底）。
 */
object DreamRules {

    private const val POSITIVE = 1
    private const val NEGATIVE = -1
    private const val NEUTRAL = 0

    private val POSITIVE_WORDS = listOf("喜欢", "热爱", "偏好", "接受", "可以", "愿意", "支持", "推荐", "赞成", "爱好")
    private val NEGATIVE_WORDS = listOf("讨厌", "反感", "拒绝", "不可以", "不能", "不愿意", "反对", "取消", "不再", "讨厌了")

    /** 情感极性：正 / 负 / 中性（词表命中计分，多词累计） */
    fun positivePolarity(text: String): Int {
        var score = 0
        POSITIVE_WORDS.forEach { if (text.contains(it)) score++ }
        NEGATIVE_WORDS.forEach { if (text.contains(it)) score-- }
        return if (score > 0) POSITIVE else if (score < 0) NEGATIVE else NEUTRAL
    }

    /**
     * 归一化字符集相似度：去空白/标点/小写后，取双字符 n-gram 的 Jaccard。
     * 相同文本 = 1.0；近似重复（≤1 处措辞差异）通常 ≥ 0.88。
     */
    fun similarity(a: String, b: String): Float {
        val normA = normalize(a)
        val normB = normalize(b)
        if (normA.isEmpty() || normB.isEmpty()) return 0f
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

    private fun bigrams(text: String): Set<String> =
        buildSet {
            for (i in 0 until text.length - 1) add(text.substring(i, i + 2))
        }
}
