package com.example.osmemory.core.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TextExtractorTest {

    @Test
    fun `markdown 围栏包裹的 JSON 可解析`() {
        val reply = """
            ```json
            {"title": "跑步偏好", "category": "偏好风格", "tags": ["西湖", "跑步"], "entities": ["西湖"], "confidence": 0.9, "sensitivity": 0}
            ```
        """.trimIndent()
        val m = TextExtractor.parse(reply)
        assertEquals("跑步偏好", m.title)
        assertEquals("偏好风格", m.category)
        assertEquals(listOf("西湖", "跑步"), m.tags)
        assertEquals(0.9, m.confidence, 1e-6)
        assertFalse(m.sensitivity)
    }

    @Test
    fun `前后缀说明文字不影响解析`() {
        val reply = "好的，以下是抽取结果：{\"title\":\"mentor 周会\",\"category\":\"日程事件\",\"tags\":[\"会议\",\"周会\"],\"entities\":[],\"confidence\":0.8,\"sensitivity\":0} 请查收"
        val m = TextExtractor.parse(reply)
        assertEquals("日程事件", m.category)
        assertEquals(listOf("会议", "周会"), m.tags)
    }

    @Test
    fun `分类不在封闭集合内归为其他`() {
        val reply = "{\"title\":\"x\",\"category\":\"随便写的分类\",\"tags\":[],\"confidence\":0.5}"
        assertEquals(TextExtractor.CATEGORY_OTHER, TextExtractor.parse(reply).category)
    }

    @Test
    fun `空对象解析出默认值`() {
        val m = TextExtractor.parse("{}")
        assertEquals("未命名记忆", m.title)
        assertEquals(TextExtractor.CATEGORY_OTHER, m.category)
        assertTrue(m.tags.isEmpty())
        assertEquals(0.5, m.confidence, 1e-6)
        assertFalse(m.sensitivity)
    }

    @Test
    fun `sensitivity 为 1 时标记敏感`() {
        val reply = "{\"title\":\"t\",\"category\":\"其他\",\"tags\":[],\"confidence\":0.5,\"sensitivity\":1}"
        assertTrue(TextExtractor.parse(reply).sensitivity)
    }

    @Test
    fun `无 JSON 内容抛出异常供上层降级`() {
        assertThrows(IllegalStateException::class.java) {
            TextExtractor.parse("抱歉，我没法解析这条内容")
        }
    }

    @Test
    fun `被截断的 JSON 抛出异常供上层降级`() {
        assertThrows(IllegalStateException::class.java) {
            TextExtractor.parse("{\"title\":\"周末计划\",\"category\":\"日程事件\",\"tags\":[\"周末\"]")
        }
    }

    @Test
    fun `降级抽取返回低置信度与原文标题`() {
        val m = TextExtractor.degraded("我周末喜欢去西湖跑步", "console")
        assertEquals("我周末喜欢去西湖跑步", m.title)
        assertEquals(TextExtractor.CATEGORY_OTHER, m.category)
        assertTrue(m.confidence < 0.5)
    }
}
