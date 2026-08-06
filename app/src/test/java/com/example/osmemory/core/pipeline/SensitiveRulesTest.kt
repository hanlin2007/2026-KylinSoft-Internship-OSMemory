package com.example.osmemory.core.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveRulesTest {

    @Test
    fun `普通文本不命中敏感规则`() {
        assertTrue(SensitiveRules.hits("我周末喜欢去西湖边跑步，一般跑 5 公里左右").isEmpty())
    }

    @Test
    fun `18位身份证号命中`() {
        val hits = SensitiveRules.hits("我的身份证号是 110101199001011234，请帮我记住")
        assertTrue(hits.contains("身份证"))
    }

    @Test
    fun `银行卡关键词命中`() {
        assertTrue(SensitiveRules.hits("这张银行卡是信用卡").contains("银行卡"))
    }

    @Test
    fun `密码关键词命中且大小写不敏感`() {
        val hits = SensitiveRules.hits("我的支付 PIN 码是 123456")
        assertTrue(hits.contains("密码口令"))
    }

    @Test
    fun `家庭住址命中`() {
        assertTrue(SensitiveRules.hits("家庭住址是杭州市西湖区文三路 100 号").contains("家庭住址"))
    }

    @Test
    fun `多规则同时命中返回去重列表`() {
        val hits = SensitiveRules.hits("银行卡密码 888888，家庭住址在西湖区")
        assertEquals(setOf("银行卡", "密码口令", "家庭住址"), hits.toSet())
    }

    @Test
    fun `门控输出敏感等级`() {
        val gate = SecurityGate()
        assertEquals(1, gate.evaluate("我周末喜欢去西湖跑步").policyLevel)
        assertEquals(2, gate.evaluate("我的身份证号是 110101199001011234").policyLevel)
    }
}
