package com.example.osmemory.phase3.classifier

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/** A model-proposed, memory-derived file category. */
data class CategorySuggestion(
    val name: String,
    val reason: String = ""
)

/**
 * Pure JSON parsing and category cleaning for the phase-three classifier.
 *
 * There are deliberately no Android dependencies in this object so malformed model replies,
 * normalization, default-category filtering and de-duplication can be covered by local JVM tests.
 */
object CategorySuggestionParser {

    data class Result(
        val categories: List<CategorySuggestion>,
        val candidateCount: Int,
        val rejectedCount: Int
    )

    /**
     * Accepts the documented object shape (`{"categories": [...]}`) and a bare JSON array as a
     * defensive fallback. Array entries can be strings or objects containing `name` and `reason`.
     */
    fun parseAndClean(
        raw: String,
        blockedNames: Collection<String> = emptyList(),
        maxCategories: Int = 8
    ): Result {
        val candidates = parseCandidates(raw)
        val categories = clean(
            candidates = candidates,
            blockedNames = blockedNames,
            maxCategories = maxCategories
        )
        return Result(
            categories = categories,
            candidateCount = candidates.size,
            rejectedCount = (candidates.size - categories.size).coerceAtLeast(0)
        )
    }

    /** Cleans an already parsed list; useful for testing normalization independently of JSON. */
    fun clean(
        candidates: Collection<CategorySuggestion>,
        blockedNames: Collection<String> = emptyList(),
        maxCategories: Int = 8
    ): List<CategorySuggestion> {
        if (maxCategories <= 0) return emptyList()

        val seen = blockedNames
            .mapNotNull(::normalizeComparisonKey)
            .toMutableSet()
        val cleaned = mutableListOf<CategorySuggestion>()

        for (candidate in candidates) {
            val name = cleanName(candidate.name) ?: continue
            val key = normalizeComparisonKey(name) ?: continue
            if (key in seen || key in GENERIC_CATEGORY_KEYS) continue

            seen += key
            cleaned += CategorySuggestion(
                name = name,
                reason = cleanReason(candidate.reason)
            )
            if (cleaned.size >= maxCategories) break
        }
        return cleaned
    }

    /** Returns a display-safe category name, or null for broad/noisy/invalid values. */
    fun cleanName(raw: String): String? {
        val value = raw
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trim('"', '\'', '`', '“', '”', '‘', '’')
            .replace(Regex("^[\\-–—•·*#]+\\s*"), "")
            .trim()

        if (value.isBlank()) return null
        val codePointCount = value.codePointCount(0, value.length)
        if (codePointCount !in 1..16) return null
        if (value.any { it in FORBIDDEN_NAME_CHARACTERS }) return null
        if (value.contains(Regex("[。！？!?；;]"))) return null
        return value
    }

    private fun parseCandidates(raw: String): List<CategorySuggestion> {
        val jsonText = extractJsonContainer(raw)
            ?: throw IllegalArgumentException("模型回复中没有完整的类别 JSON")
        val root = try {
            JSONTokener(jsonText).nextValue()
        } catch (error: Exception) {
            throw IllegalArgumentException("类别 JSON 无法解析：${error.message ?: "格式错误"}", error)
        }

        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                CATEGORY_ARRAY_KEYS.firstNotNullOfOrNull { key -> root.optJSONArray(key) }
                    ?: throw IllegalArgumentException("类别 JSON 缺少 categories 数组")
            }
            else -> throw IllegalArgumentException("类别 JSON 根节点必须是对象或数组")
        }

        return buildList {
            for (index in 0 until array.length()) {
                when (val value = array.opt(index)) {
                    is String -> add(CategorySuggestion(value))
                    is JSONObject -> {
                        val name = NAME_KEYS.firstNotNullOfOrNull { key ->
                            value.optString(key).trim().takeIf(String::isNotEmpty)
                        }.orEmpty()
                        val reason = REASON_KEYS.firstNotNullOfOrNull { key ->
                            value.optString(key).trim().takeIf(String::isNotEmpty)
                        }.orEmpty()
                        add(CategorySuggestion(name, reason))
                    }
                }
            }
        }
    }

    private fun cleanReason(raw: String): String = raw
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .take(MAX_REASON_LENGTH)

    private fun normalizeComparisonKey(value: String): String? = cleanName(value)
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("[\\s_\\-—–]+"), "")
        ?.takeIf(String::isNotBlank)

    /** Extracts the first balanced object or array while respecting quoted strings. */
    private fun extractJsonContainer(raw: String): String? {
        val text = stripMarkdownFence(raw)
        val objectStart = text.indexOf('{').takeIf { it >= 0 }
        val arrayStart = text.indexOf('[').takeIf { it >= 0 }
        val start = listOfNotNull(objectStart, arrayStart).minOrNull() ?: return null
        val opening = text[start]
        val closing = if (opening == '{') '}' else ']'

        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    opening -> depth++
                    closing -> {
                        depth--
                        if (depth == 0) return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun stripMarkdownFence(raw: String): String {
        var value = raw.trim()
        if (value.startsWith("```")) {
            val firstLineEnd = value.indexOf('\n')
            value = if (firstLineEnd >= 0) value.substring(firstLineEnd + 1) else ""
        }
        if (value.trimEnd().endsWith("```")) {
            value = value.trimEnd().dropLast(3)
        }
        return value.trim()
    }

    private const val MAX_REASON_LENGTH = 160
    private val CATEGORY_ARRAY_KEYS = listOf("categories", "items", "suggestions")
    private val NAME_KEYS = listOf("name", "category", "label", "title")
    private val REASON_KEYS = listOf("reason", "rationale", "description")
    private val FORBIDDEN_NAME_CHARACTERS = setOf('{', '}', '[', ']', ':')
    private val GENERIC_CATEGORY_KEYS = setOf(
        "其他", "其它", "未分类", "默认", "默认类别", "分类", "类别", "无",
        "none", "null", "unknown", "uncategorized"
    )
}
