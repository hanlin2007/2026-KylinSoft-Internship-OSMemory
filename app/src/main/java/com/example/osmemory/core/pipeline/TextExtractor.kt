package com.example.osmemory.core.pipeline

import com.example.osmemory.core.model.JsonTools
import com.example.osmemory.core.model.ModelProvider
import com.example.osmemory.core.model.TextTools
import org.json.JSONObject

/**
 * 记忆结构化抽取（LLM 驱动，杜绝规则引擎）
 *
 * 让模型从一条原始记忆文本中抽出：
 *  - title        一句话标题
 *  - category     封闭分类（八类）
 *  - tags         3~5 个遴选标签（对应 PPT "遴选记忆标签"）
 *  - entities     关键实体（人/地点/时间/项目等）
 *  - confidence   置信度 0~1
 *  - sensitivity  敏感标记 0/1（AI 敏感分类增强通道）
 *
 * 网络/解析失败由调用方降级（原文入库 + 低置信度），本类只管"尽力抽取"。
 */
object TextExtractor {

    const val CATEGORY_OTHER = "其他"

    /** 封闭分类（对齐 PPT 第 9 页：长期画像/日程/项目/偏好/任务/关系/地点设备） */
    val CATEGORIES = listOf(
        "用户画像", "日程事件", "项目上下文", "偏好风格", "任务轨迹", "联系人关系", "地点设备", "其他"
    )

    /** 抽取结果（缺失字段有默认值，保证永不抛异常） */
    data class ExtractedMemory(
        val title: String,
        val category: String,
        val tags: List<String>,
        val entities: List<String>,
        val confidence: Double,
        val sensitivity: Boolean
    )

    private val SYSTEM_PROMPT = """
        你是 OS Memory 系统的记忆抽取模块。用户会给你一条待记忆的原始文本。
        请输出且只输出一个 JSON 对象（不要任何解释、不要 markdown 围栏），字段如下：
        {
          "title": "一句话标题（≤20字）",
          "category": "分类，只能取：用户画像/日程事件/项目上下文/偏好风格/任务轨迹/联系人关系/地点设备/其他",
          "tags": ["标签1", "标签2", "标签3"],   // 3~5 个，必须从原文中遴选，不要编造
          "entities": ["实体1", "实体2"],          // 人/地点/时间/项目等关键实体，没有则给空数组
          "confidence": 0.9,                        // 你对内容结构的把握，0~1
          "sensitivity": 0                          // 0=普通，1=敏感（涉及身份证/银行卡/密码/住址/健康/生物信息等）
        }
    """.trimIndent()

    fun buildUserPrompt(rawText: String): String =
        "待记忆的原始文本：\n$rawText"

    /** 调用模型抽取；任何失败抛异常（由调用方降级） */
    suspend fun extract(provider: ModelProvider, rawText: String): ExtractedMemory {
        val reply = provider.complete(SYSTEM_PROMPT, buildUserPrompt(rawText), 0.2)
        return parse(reply)
    }

    /**
     * 解析模型回复为 [ExtractedMemory]，健壮性处理：
     * markdown fence / 前后缀文字 / 字段缺失 / 分类不在封闭集合内 → 归入"其他"。
     */
    fun parse(rawReply: String): ExtractedMemory {
        val jsonText = JsonTools.extractBalancedJson(rawReply)
            ?: throw IllegalStateException("回复中未找到合法 JSON：${rawReply.take(200)}")
        val json = JSONObject(jsonText)

        val title = JsonTools.optString(json, "title", "").trim()
            .takeIf { it.isNotBlank() } ?: "未命名记忆"

        val categoryRaw = JsonTools.optString(json, "category", "").trim()
        val category = if (CATEGORIES.contains(categoryRaw)) categoryRaw else CATEGORY_OTHER

        val tags = JsonTools.optStringArray(json, "tags").take(5)

        val entities = JsonTools.optStringArray(json, "entities")

        val confidence = JsonTools.optDouble(json, "confidence", 0.5)
            .coerceIn(0.0, 1.0)

        val sensitivity = JsonTools.optInt(json, "sensitivity", 0) == 1

        return ExtractedMemory(
            title = title,
            category = category,
            tags = tags,
            entities = entities,
            confidence = confidence,
            sensitivity = sensitivity
        )
    }

    /** 降级抽取：模型不可用时使用（原文标题 + 其他分类 + 低置信度） */
    fun degraded(rawText: String, source: String): ExtractedMemory = ExtractedMemory(
        title = TextTools.truncate(rawText, 20),
        category = CATEGORY_OTHER,
        tags = listOfNotNull(source.takeIf { it.isNotBlank() }),
        entities = emptyList(),
        confidence = 0.3,
        sensitivity = false
    )
}
