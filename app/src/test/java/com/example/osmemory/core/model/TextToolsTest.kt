package com.example.osmemory.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TextToolsTest {

    @Test
    fun `归一化去除全部空白`() {
        assertEquals("我周末喜欢去西湖跑步", TextTools.normalize(" 我 周末 喜欢  去西湖跑步 "))
    }

    @Test
    fun `归一化哈希稳定且对空白大小写不敏感`() {
        assertEquals(TextTools.normalizeHash("ABC"), TextTools.normalizeHash("abc "))
        assertNotEquals(TextTools.normalizeHash("abc"), TextTools.normalizeHash("abd"))
    }

    @Test
    fun `查询词切分过滤单字`() {
        assertEquals(listOf("西湖", "跑步"), TextTools.tokenize("去 西湖 跑步！"))
        assertEquals(listOf("mentor", "周会"), TextTools.tokenize("mentor 的周会"))
    }

    @Test
    fun `截断追加省略号`() {
        assertEquals("abcd…", TextTools.truncate("abcdefgh", 4))
        assertEquals("abc", TextTools.truncate("abc", 4))
    }
}
