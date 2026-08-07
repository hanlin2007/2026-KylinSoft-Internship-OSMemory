package com.example.osmemory.core.model

/**
 * 纯 Kotlin JSON 提取工具（JVM 可测）
 *
 * 大模型输出并不总是严格的 JSON（可能带 markdown fence、前后缀说明、被截断），
 * 这里提供两阶段健壮化：剥 fence → 括号平衡扫描取合法 JSON 子串。
 */
object JsonTools {

    /** 剥离 ```json ... ``` / ``` ... ``` 围栏 */
    fun stripFences(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            // 去掉开头的 ```json / ``` 行
            val firstNewline = text.indexOf('\n')
            text = if (firstNewline > 0) text.substring(firstNewline + 1) else ""
        }
        if (text.endsWith("```")) {
            text = text.dropLast(3)
        }
        return text.trim()
    }

    /**
     * 从文本中提取第一个括号平衡的 JSON 对象子串。
     * 正确处理字符串内的引号转义与嵌套花括号；找不到返回 null。
     */
    fun extractBalancedJson(raw: String): String? {
        val text = stripFences(raw)
        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * 从文本中提取第一个括号平衡的 JSON 数组子串（[a, b, c]）。
     * 用于模型直接返回裸数组的场景（如语义重排输出 memoId 列表）；找不到返回 null。
     */
    fun extractBalancedArray(raw: String): String? {
        val text = stripFences(raw)
        val start = text.indexOf('[')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    /** 从 JSON 对象中安全取字符串字段（缺失/类型不符返回默认值） */
    fun optString(json: org.json.JSONObject, key: String, default: String): String =
        if (json.has(key) && !json.isNull(key)) json.optString(key, default) else default

    /** 从 JSON 对象中安全取字符串数组字段（缺失返回空列表） */
    fun optStringArray(json: org.json.JSONObject, key: String): List<String> {
        if (!json.has(key)) return emptyList()
        return try {
            val arr = json.optJSONArray(key) ?: return emptyList()
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).trim().takeIf { it.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 从 JSON 对象中安全取数值字段 */
    fun optDouble(json: org.json.JSONObject, key: String, default: Double): Double =
        if (json.has(key) && !json.isNull(key)) json.optDouble(key, default) else default

    /** 从 JSON 对象中安全取整数字段 */
    fun optInt(json: org.json.JSONObject, key: String, default: Int): Int =
        if (json.has(key) && !json.isNull(key)) json.optInt(key, default) else default

    /** 把键值对拼成 JSON 字符串（日志 extra 用，避免额外依赖序列化库） */
    fun buildJson(vararg pairs: Pair<String, Any?>): String {
        val json = org.json.JSONObject()
        pairs.forEach { (k, v) ->
            when (v) {
                null -> json.put(k, org.json.JSONObject.NULL)
                is Int -> json.put(k, v)
                is Long -> json.put(k, v)
                is Double -> json.put(k, v)
                is Boolean -> json.put(k, v)
                is String -> json.put(k, v)
                else -> json.put(k, v.toString())
            }
        }
        return json.toString()
    }
}
