package com.example.osmemory.core.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DreamRules 纯逻辑测试：相似度 / 情感极性 / 句读拆分
 * （LLM 不可用时的降级兜底，全部确定性规则）。
 */
class DreamRulesTest {

    // ---------- similarity（归一化 bigram Jaccard） ----------

    @Test
    fun `相同文本相似度为 1`() {
        assertEquals(1f, DreamRules.similarity("我喜欢喝咖啡", "我喜欢喝咖啡"), 0f)
    }

    @Test
    fun `标点空格大小写差异不影响相似度`() {
        assertEquals(1f, DreamRules.similarity("我每天早上七点跑步。", "我每天早上七点跑步"), 0f)
        assertEquals(1f, DreamRules.similarity("Hello World", "hello world!"), 0f)
    }

    @Test
    fun `一字之差低于完全重复但远高于无关文本`() {
        val close = DreamRules.similarity("我每天早上七点起床跑步", "我每天早上七点起床去跑步")
        val far = DreamRules.similarity("我每天早上七点起床跑步", "今天股市大涨三倍收益翻番")
        assertTrue("近似重复应显著高于无关文本：$close vs $far", close > far)
        assertTrue("近似重复应低于完全重复：$close", close < 1f)
        assertTrue("无关文本相似度应很低：$far", far < 0.4f)
    }

    @Test
    fun `空文本相似度为 0`() {
        assertEquals(0f, DreamRules.similarity("", "任何内容"), 0f)
        assertEquals(0f, DreamRules.similarity("任何内容", ""), 0f)
        assertEquals(0f, DreamRules.similarity("", ""), 0f)
    }

    // ---------- positivePolarity（情感极性） ----------

    @Test
    fun `正面词命中返回正极性`() {
        assertEquals(1, DreamRules.positivePolarity("我喜欢跑步和游泳"))
        assertEquals(1, DreamRules.positivePolarity("我支持这个方案，愿意尝试"))
    }

    @Test
    fun `负面词命中返回负极性`() {
        assertEquals(-1, DreamRules.positivePolarity("我讨厌雨天出门"))
        assertEquals(-1, DreamRules.positivePolarity("我不愿意加班，决定取消今晚的安排"))
    }

    @Test
    fun `正负词同时命中互相抵消`() {
        assertEquals(0, DreamRules.positivePolarity("我喜欢咖啡但讨厌牛奶"))
    }

    @Test
    fun `无词命中返回中性`() {
        assertEquals(0, DreamRules.positivePolarity("今天天气不错，出门走了走"))
    }

    // ---------- ruleSplit（句读拆分） ----------

    @Test
    fun `多句读长文本拆成多条原子记忆`() {
        val pieces = DreamRules.ruleSplit("我每天早上七点起床跑步三十分钟。晚上九点再走二十分钟。")
        assertEquals(2, pieces.size)
        assertEquals("我每天早上七点起床跑步三十分钟", pieces[0])
        assertEquals("晚上九点再走二十分钟", pieces[1])
    }

    @Test
    fun `中文分号与英文句点均可拆分`() {
        val pieces = DreamRules.ruleSplit("我喜欢跑步；同时也喜欢游泳。周末常去爬山")
        assertEquals(3, pieces.size)
    }

    @Test
    fun `单句无句读不拆分`() {
        assertEquals(emptyList<String>(), DreamRules.ruleSplit("我每天早上七点起床跑步"))
    }

    @Test
    fun `拆分出的段落过短视为无效`() {
        // 其中一段只有 2 个字符 → 整条不拆（防止拆出碎片）
        assertEquals(emptyList<String>(), DreamRules.ruleSplit("我跑步。他游泳"))
    }
}
