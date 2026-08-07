package com.example.osmemory.phase3.classifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CategorySuggestionParserTest {

    @Test
    fun `解析文档约定的 categories 对象数组`() {
        val result = CategorySuggestionParser.parseAndClean(
            """{
                "categories": [
                    {"name":"AI","reason":"多条记忆涉及大模型"},
                    {"name":"实习","reason":"包含入职和项目记录"}
                ]
            }""".trimIndent()
        )

        assertEquals(
            listOf(
                CategorySuggestion("AI", "多条记忆涉及大模型"),
                CategorySuggestion("实习", "包含入职和项目记录")
            ),
            result.categories
        )
        assertEquals(2, result.candidateCount)
        assertEquals(0, result.rejectedCount)
    }

    @Test
    fun `解析裸数组并兼容字符串与别名字段`() {
        val result = CategorySuggestionParser.parseAndClean(
            """[
                "世界杯",
                {"category":"实习","rationale":"求职相关"},
                {"label":"AI","description":"模型项目"},
                {"title":"读书","reason":"阅读记录"}
            ]""".trimIndent()
        )

        assertEquals(
            listOf(
                CategorySuggestion("世界杯"),
                CategorySuggestion("实习", "求职相关"),
                CategorySuggestion("AI", "模型项目"),
                CategorySuggestion("读书", "阅读记录")
            ),
            result.categories
        )
    }

    @Test
    fun `兼容 items 和 suggestions 数组键`() {
        assertEquals(
            listOf(CategorySuggestion("摄影")),
            CategorySuggestionParser.parseAndClean("""{"items":["摄影"]}""").categories
        )
        assertEquals(
            listOf(CategorySuggestion("编程")),
            CategorySuggestionParser.parseAndClean("""{"suggestions":["编程"]}""").categories
        )
    }

    @Test
    fun `解析 markdown fence 和前后说明文字`() {
        val result = CategorySuggestionParser.parseAndClean(
            "```json\n{\"categories\":[{\"name\":\"AI\",\"reason\":\"包含 } 和 ] 字符\"}]}\n```\n额外说明"
        )

        assertEquals(
            listOf(CategorySuggestion("AI", "包含 } 和 ] 字符")),
            result.categories
        )
    }

    @Test
    fun `跳过数组中的非字符串非对象元素`() {
        val result = CategorySuggestionParser.parseAndClean(
            """{"categories":[42,true,null,"旅行"]}"""
        )

        assertEquals(listOf(CategorySuggestion("旅行")), result.categories)
        assertEquals(1, result.candidateCount)
        assertEquals(0, result.rejectedCount)
    }

    @Test
    fun `无完整 JSON 时抛出可诊断异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            CategorySuggestionParser.parseAndClean("模型建议：AI、实习")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CategorySuggestionParser.parseAndClean("""{"categories":["AI"]""")
        }
    }

    @Test
    fun `根节点类型或数组字段错误时抛出异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            CategorySuggestionParser.parseAndClean("123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CategorySuggestionParser.parseAndClean("""{"category":"AI"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CategorySuggestionParser.parseAndClean("""{"categories":"AI"}""")
        }
    }

    @Test
    fun `名称清洗折叠空白移除引号和列表前缀`() {
        assertEquals("AI 实习", CategorySuggestionParser.cleanName(" \"AI\n\t 实习\" "))
        assertEquals("世界杯", CategorySuggestionParser.cleanName("  •   世界杯  "))
        assertEquals("读书", CategorySuggestionParser.cleanName("`读书`"))
    }

    @Test
    fun `名称拒绝空白超长结构字符和句子标点`() {
        assertNull(CategorySuggestionParser.cleanName(" \n\t "))
        assertNull(CategorySuggestionParser.cleanName("一二三四五六七八九十一二三四五六七"))
        assertNull(CategorySuggestionParser.cleanName("AI:项目"))
        assertNull(CategorySuggestionParser.cleanName("{旅行}"))
        assertNull(CategorySuggestionParser.cleanName("这是一个类别。"))
        assertNull(CategorySuggestionParser.cleanName("工作!"))
    }

    @Test
    fun `名称长度按 Unicode 码点而不是 UTF16 单元计算`() {
        assertEquals("😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀", CategorySuggestionParser.cleanName("😀".repeat(16)))
        assertNull(CategorySuggestionParser.cleanName("😀".repeat(17)))
    }

    @Test
    fun `清洗过滤默认泛化类别`() {
        val cleaned = CategorySuggestionParser.clean(
            listOf(
                CategorySuggestion("其他"),
                CategorySuggestion("未分类"),
                CategorySuggestion("NONE"),
                CategorySuggestion("Unknown"),
                CategorySuggestion("AI")
            )
        )

        assertEquals(listOf(CategorySuggestion("AI")), cleaned)
    }

    @Test
    fun `清洗按大小写空格横线和下划线去重并保留首项`() {
        val cleaned = CategorySuggestionParser.clean(
            listOf(
                CategorySuggestion("AI 项目", "首项"),
                CategorySuggestion("ai-项目", "重复项"),
                CategorySuggestion("AI_项目", "重复项"),
                CategorySuggestion("世界杯", "保留")
            )
        )

        assertEquals(
            listOf(
                CategorySuggestion("AI 项目", "首项"),
                CategorySuggestion("世界杯", "保留")
            ),
            cleaned
        )
    }

    @Test
    fun `清洗排除已有默认或动态类别`() {
        val cleaned = CategorySuggestionParser.clean(
            candidates = listOf(
                CategorySuggestion("家 庭"),
                CategorySuggestion("ai-实习"),
                CategorySuggestion("旅行摄影")
            ),
            blockedNames = listOf("家庭", "AI_实习")
        )

        assertEquals(listOf(CategorySuggestion("旅行摄影")), cleaned)
    }

    @Test
    fun `清洗遵守最大类别数且非正上限返回空列表`() {
        val candidates = listOf(
            CategorySuggestion("AI"),
            CategorySuggestion("实习"),
            CategorySuggestion("世界杯")
        )

        assertEquals(
            listOf(CategorySuggestion("AI"), CategorySuggestion("实习")),
            CategorySuggestionParser.clean(candidates, maxCategories = 2)
        )
        assertEquals(emptyList<CategorySuggestion>(), CategorySuggestionParser.clean(candidates, maxCategories = 0))
        assertEquals(emptyList<CategorySuggestion>(), CategorySuggestionParser.clean(candidates, maxCategories = -1))
    }

    @Test
    fun `原因清洗折叠空白并限制一百六十字符`() {
        val cleaned = CategorySuggestionParser.clean(
            listOf(CategorySuggestion("AI", " 第一行\n\t第二行   " + "x".repeat(200)))
        ).single()

        assertEquals(160, cleaned.reason.length)
        assertEquals("第一行 第二行 ", cleaned.reason.take(8))
    }

    @Test
    fun `结果统计包含默认类别重复项和上限截断造成的拒绝数`() {
        val result = CategorySuggestionParser.parseAndClean(
            raw = """{"categories":["家庭","AI","ai","实习","世界杯"]}""",
            blockedNames = listOf("家庭"),
            maxCategories = 2
        )

        assertEquals(listOf(CategorySuggestion("AI"), CategorySuggestion("实习")), result.categories)
        assertEquals(5, result.candidateCount)
        assertEquals(3, result.rejectedCount)
    }
}
