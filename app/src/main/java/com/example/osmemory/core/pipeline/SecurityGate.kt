package com.example.osmemory.core.pipeline

/**
 * 安全门控（对应 PPT "Security Gate / Sensitive Data Fence"）
 *
 * 确定性栅栏：SensitiveRules 命中即标记 policyLevel=2（敏感）。
 * AI 敏感分类为增强通道，在 TextExtractor 的 sensitivity 字段中返回（见 MemoryPipeline）。
 * 任何路径都不会因为"没识别出来"而降低安全基线（栅栏优先，取并集）。
 */
class SecurityGate {

    /** 门控结果 */
    data class GateResult(
        val sensitive: Boolean,
        val matchedRules: List<String>,
        /** 0=公开 1=普通 2=敏感 */
        val policyLevel: Int
    )

    fun evaluate(content: String): GateResult {
        val matched = SensitiveRules.hits(content)
        val sensitive = matched.isNotEmpty()
        return GateResult(
            sensitive = sensitive,
            matchedRules = matched,
            policyLevel = if (sensitive) 2 else 1
        )
    }
}
