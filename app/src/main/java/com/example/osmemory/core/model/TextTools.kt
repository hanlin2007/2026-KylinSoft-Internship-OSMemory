package com.example.osmemory.core.model

/**
 * 文本工具（纯 Kotlin，JVM 可测）
 */
object TextTools {

    /** 归一化：去首尾空白、统一大小写、压缩所有空白字符（去重用） */
    fun normalize(text: String): String =
        text.trim().lowercase().replace(WHITESPACE_REGEX, "")

    /** 归一化文本的 SHA-256（去重主键，稳定且抗碰撞） */
    fun normalizeHash(text: String): String =
        sha256Hex(normalize(text))

    private fun sha256Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** 截断并追加省略号 */
    fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        return text.take(max) + "…"
    }

    /**
     * 查询词切分：按空白/常见标点分词后，
     * 含中文的部分做 2 字滑窗二元组（提高中文召回率），
     * 纯拉丁/数字部分整体保留；丢弃以停用词开头的二元组（如"的周"）。
     */
    fun tokenize(query: String): List<String> {
        val parts = query.trim()
            .split(TOKEN_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val tokens = mutableListOf<String>()
        for (part in parts) {
            val containsCjk = part.any { it.code in CJK_RANGE }
            if (!containsCjk || part.length <= 2) {
                tokens += part
                continue
            }
            // 中文长片段 → 2 字滑窗
            for (i in 0..part.length - 2) {
                val gram = part.substring(i, i + 2)
                if (gram.first().code !in CJK_RANGE) continue
                if (gram.first() in STOP_START_CHARS) continue
                tokens += gram
            }
        }
        return tokens.distinct().filter { it.length >= 2 }
    }

    private val CJK_RANGE = 0x4E00..0x9FFF
    private val STOP_START_CHARS = "的了是在和与就去来我你他她它们么吗吧呢啊把被让对从向为着"

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val TOKEN_SPLIT_REGEX = Regex("[\\s,，。.!！?？;；:：、/\\\\|（）()\\[\\]【】\"'“”‘’]+")
}
