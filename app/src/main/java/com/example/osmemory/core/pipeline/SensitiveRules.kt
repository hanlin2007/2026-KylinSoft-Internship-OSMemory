package com.example.osmemory.core.pipeline

/**
 * 确定性敏感识别规则（安全硬能力，非 AI 替代）
 *
 * 对应 PPT "统一安全治理层：Sensitive Data Fence"。
 * 这是安全基线（必须确定性生效、离线可用），
 * AI 敏感分类作为增强通道叠加在结构化抽取中（见 TextExtractor 的 sensitivity 字段）。
 */
object SensitiveRules {

    /** 规则名 -> 命中关键词 */
    val RULES: List<Pair<String, List<String>>> = listOf(
        "身份证" to listOf("身份证", "身份证号", "居民身份证", "身份证号码"),
        "银行卡" to listOf("银行卡", "信用卡", "借记卡", "卡号"),
        "密码口令" to listOf("密码", "口令", "支付密码", "PIN"),
        "手机号码" to listOf("手机号", "手机号码", "电话号码", "联系方式"),
        "家庭住址" to listOf("家庭住址", "住址", "门牌号", "家庭地址"),
        "证件信息" to listOf("护照", "驾驶证", "军官证", "港澳通行证"),
        "账户信息" to listOf("银行账户", "账号", "登录账号"),
        "生物信息" to listOf("指纹", "虹膜", "人脸识别", "声纹"),
        "医疗健康" to listOf("病历", "诊断结果", "体检报告"),
    )

    /** 18 位身份证号（含末位 X） */
    private val ID_CARD_REGEX = Regex("\\b\\d{17}[0-9Xx]\\b")

    /** 返回命中的规则名列表（空 = 未命中敏感） */
    fun hits(text: String): List<String> {
        val matched = mutableListOf<String>()
        for ((ruleName, keywords) in RULES) {
            if (keywords.any { text.contains(it, ignoreCase = true) }) {
                matched += ruleName
            }
        }
        if (ID_CARD_REGEX.containsMatchIn(text)) matched += "身份证"
        return matched.distinct()
    }
}
