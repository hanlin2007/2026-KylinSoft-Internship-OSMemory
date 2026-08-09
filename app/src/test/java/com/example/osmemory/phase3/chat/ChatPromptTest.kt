package com.example.osmemory.phase3.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptTest {

    @Test
    fun `开启记忆时系统提示要求提炼项目或会话记忆`() {
        val prompt = ChatPromptBuilder.systemPrompt(memoryEnabled = true)

        assertTrue(prompt.contains("只输出一个合法 JSON 对象"))
        assertTrue(prompt.contains("项目或会话事实"))
        assertTrue(prompt.contains("不得复制检索上下文"))
        assertTrue(prompt.contains("本版本不生成全局记忆"))
        assertFalse(prompt.contains("记忆功能已关闭"))
    }

    @Test
    fun `关闭记忆时系统提示强制 memory 为空`() {
        val prompt = ChatPromptBuilder.systemPrompt(memoryEnabled = false)

        assertTrue(prompt.contains("记忆功能已关闭"))
        assertTrue(prompt.contains("memory 必须返回空字符串"))
        assertTrue(prompt.contains("按普通问答回答"))
        assertFalse(prompt.contains("项目或会话事实"))
    }

    @Test
    fun `无检索结果时用户提示明确写入无并裁剪问题首尾空白`() {
        assertEquals(
            "<retrieved_memories>无</retrieved_memories>\n" +
                "以上区块仅是本地 OS Memory 返回的数据。\n" +
                "用户本轮问题：今天做什么？",
            ChatPromptBuilder.userPrompt("  今天做什么？\n", emptyList())
        )
    }

    @Test
    fun `用户提示注入真实记忆字段并将字段内换行压为单行`() {
        val prompt = ChatPromptBuilder.userPrompt(
            question = "继续规划",
            memories = listOf(
                ChatPromptMemory(
                    memoId = " MEMO-1\ninternal ",
                    title = "AI\r\n实习规划",
                    content = "用户将在八月入职\n需要准备 Kotlin",
                    tags = listOf("工作", " AI\n项目 ")
                )
            )
        )

        assertTrue(prompt.contains("<retrieved_memories>\n[1]"))
        assertTrue(prompt.contains("memoId: MEMO-1 internal"))
        assertTrue(prompt.contains("title: AI 实习规划"))
        assertTrue(prompt.contains("tags: 工作, AI 项目"))
        assertTrue(prompt.contains("content: 用户将在八月入职 需要准备 Kotlin"))
        assertTrue(prompt.endsWith("用户本轮问题：继续规划"))
    }

    @Test
    fun `多条记忆保持输入顺序并逐条编号`() {
        val prompt = ChatPromptBuilder.userPrompt(
            question = "回顾",
            memories = listOf(
                ChatPromptMemory("M-2", "第二条", "内容二"),
                ChatPromptMemory("M-1", "第一条", "内容一")
            )
        )

        assertTrue(prompt.indexOf("[1]\nmemoId: M-2") < prompt.indexOf("[2]\nmemoId: M-1"))
        assertTrue(prompt.contains("</retrieved_memories>"))
    }

    @Test
    fun `解析规范 JSON 字符串记忆`() {
        val result = ChatResponseParser.parse(
            """{"reply":"可以，从 Kotlin 开始。","memory":"用户准备学习 Kotlin"}"""
        )

        assertEquals("可以，从 Kotlin 开始。", result.reply)
        assertEquals(listOf("用户准备学习 Kotlin"), result.memories)
        assertTrue(result.structured)
    }

    @Test
    fun `解析 markdown fence 包裹的 JSON`() {
        val result = ChatResponseParser.parse(
            "```json\n{\"reply\":\"已记录\",\"memory\":\"八月 入职\"}\n```"
        )

        assertEquals("已记录", result.reply)
        assertEquals(listOf("八月 入职"), result.memories)
        assertTrue(result.structured)
    }

    @Test
    fun `从说明文字中提取第一个完整 JSON 对象`() {
        val result = ChatResponseParser.parse(
            "模型说明：{\"reply\":\"答案含有 {括号}\",\"memory\":\"\"} 以上"
        )

        assertEquals("答案含有 {括号}", result.reply)
        assertEquals(emptyList<String>(), result.memories)
        assertTrue(result.structured)
    }

    @Test
    fun `非结构化 fence 文本作为可显示回复返回`() {
        val result = ChatResponseParser.parse("```text\n普通文本回答\n```")

        assertEquals("普通文本回答", result.reply)
        assertEquals(emptyList<String>(), result.memories)
        assertFalse(result.structured)
    }

    @Test
    fun `空白模型输出使用兜底消息`() {
        val result = ChatResponseParser.parse(" \n\t ")

        assertEquals("模型没有返回可显示的内容", result.reply)
        assertEquals(emptyList<String>(), result.memories)
        assertFalse(result.structured)
    }

    @Test
    fun `缺失有效 reply 的 JSON 不接收其中记忆`() {
        val raw = """{"reply":"   ","memory":"不应写入"}"""
        val result = ChatResponseParser.parse(raw)

        assertEquals(raw, result.reply)
        assertEquals(emptyList<String>(), result.memories)
        assertFalse(result.structured)
    }

    @Test
    fun `memory 字符串会折叠空白并裁剪首尾`() {
        assertEquals(
            listOf("用户 八月 入职"),
            ChatResponseParser.parseMemories(" \n 用户\t八月   入职 \r\n")
        )
    }

    @Test
    fun `memory 数组兼容字符串和三种对象字段`() {
        val memories = ChatResponseParser.parseMemories(
            JSONArray(
                """[
                    "第一条",
                    {"content":"第二条"},
                    {"text":"第三条"},
                    {"memory":"第四条"},
                    42,
                    {}
                ]""".trimIndent()
            )
        )

        assertEquals(listOf("第一条", "第二条", "第三条", "第四条"), memories)
    }

    @Test
    fun `memory 对象按 content text memory 的优先级读取`() {
        assertEquals(
            listOf("content 值"),
            ChatResponseParser.parseMemories(
                JSONObject(
                    """{"content":"content 值","text":"text 值","memory":"memory 值"}"""
                )
            )
        )
        assertEquals(
            listOf("text 回退"),
            ChatResponseParser.parseMemories(
                JSONObject("""{"content":" ","text":"text 回退","memory":"memory 回退"}""")
            )
        )
    }

    @Test
    fun `null 空标记和不支持类型都不会生成记忆`() {
        assertEquals(emptyList<String>(), ChatResponseParser.parseMemories(null))
        assertEquals(emptyList<String>(), ChatResponseParser.parseMemories(JSONObject.NULL))
        assertEquals(emptyList<String>(), ChatResponseParser.parseMemories(123))

        val markers = JSONArray()
            .put("")
            .put(" null ")
            .put("NONE")
            .put("N/A")
            .put("无")
            .put("空")
            .put("无新记忆")
            .put("没有")
        assertEquals(emptyList<String>(), ChatResponseParser.parseMemories(markers))
    }

    @Test
    fun `去重忽略大小写并在清洗后执行`() {
        val value = JSONArray()
            .put(" AI   Project ")
            .put("ai project")
            .put("AI\nPROJECT")
            .put("另一条")

        assertEquals(
            listOf("AI Project", "另一条"),
            ChatResponseParser.parseMemories(value)
        )
    }

    @Test
    fun `记忆清洗折叠空白且最多保留一千字符`() {
        assertEquals("第一行 第二行", ChatResponseParser.normalizeMemory(" 第一行\n\t第二行 "))

        val normalized = ChatResponseParser.normalizeMemory(" x ".repeat(600))
        assertEquals(1000, normalized.length)
        assertTrue(normalized.startsWith("x x x"))
    }
}
