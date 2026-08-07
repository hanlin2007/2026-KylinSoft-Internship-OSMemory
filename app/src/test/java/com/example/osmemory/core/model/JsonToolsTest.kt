package com.example.osmemory.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class JsonToolsTest {

    @Test
    fun `剥除 markdown 围栏`() {
        assertEquals(
            "{\"a\":1}",
            JsonTools.stripFences("```json\n{\"a\":1}\n```")
        )
    }

    @Test
    fun `提取括号平衡的 JSON 子串 - 嵌套与字符串内花括号`() {
        val raw = "前缀说明 {\"a\": {\"b\": \"x{y}z\"}} 后缀说明"
        assertEquals("{\"a\": {\"b\": \"x{y}z\"}}", JsonTools.extractBalancedJson(raw))
    }

    @Test
    fun `字符串内的转义引号不影响括号扫描`() {
        val raw = "{\"quote\": \"say \\\"{hello}\\\"\"}"
        val json = JsonTools.extractBalancedJson(raw)
        assertEquals("{\"quote\": \"say \\\"{hello}\\\"\"}", json)
        assertEquals("say \"{hello}\"", JSONObject(json!!).getString("quote"))
    }

    @Test
    fun `无花括号返回 null`() {
        assertNull(JsonTools.extractBalancedJson("完全没有 JSON 的回复"))
    }

    @Test
    fun `buildJson 输出合法 JSON`() {
        val json = JsonTools.buildJson("a" to 1, "b" to "x", "c" to true, "d" to null)
        val obj = JSONObject(json)
        assertEquals(1, obj.getInt("a"))
        assertEquals("x", obj.getString("b"))
        assertEquals(true, obj.getBoolean("c"))
        assertEquals(JSONObject.NULL, obj.get("d"))
    }

    @Test
    fun `提取括号平衡的 JSON 数组`() {
        val raw = """["MEMO-1", "MEMO-2", "MEMO-3"]"""
        assertEquals("""["MEMO-1", "MEMO-2", "MEMO-3"]""", JsonTools.extractBalancedArray(raw))
    }

    @Test
    fun `markdown 围栏包裹的数组可提取`() {
        val raw = "```json\n[\"a\", \"b\"]\n```"
        assertEquals("[\"a\", \"b\"]", JsonTools.extractBalancedArray(raw))
    }

    @Test
    fun `字符串内包含括号不影响数组扫描`() {
        val raw = """["a]{b", "c]d", "e"]"""
        assertEquals("""["a]{b", "c]d", "e"]""", JsonTools.extractBalancedArray(raw))
    }

    @Test
    fun `对象内嵌数组按数组提取`() {
        assertEquals("[1,2]", JsonTools.extractBalancedArray("{\"ids\": [1,2]}"))
    }

    @Test
    fun `完全无数组返回 null`() {
        assertEquals(null, JsonTools.extractBalancedArray("完全没有数组"))
    }
}
