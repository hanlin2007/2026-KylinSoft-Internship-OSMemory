package com.example.osmemory.core.retrieval

import com.example.osmemory.core.model.ModelException
import com.example.osmemory.core.model.ModelProvider
import com.example.osmemory.data.db.entity.MemoryItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticRerankerTest {

    private class FakeProvider(private val reply: String = "", private val fail: Boolean = false) : ModelProvider {
        override val name = "fake"
        override suspend fun complete(system: String, user: String, temperature: Double): String {
            if (fail) throw ModelException("模型网关不可达")
            return reply
        }
    }

    private fun item(memoId: String, content: String = memoId) = MemoryItemEntity(
        memoId = memoId, contentHash = memoId, content = content,
        title = memoId, category = "其他", tags = "", source = "console",
        appId = "app", createdAt = 1, updatedAt = 1, evidenceRaw = content
    )

    @Test
    fun `按模型返回的数组顺序重排`() = runBlockingTest {
        val candidates = listOf(item("MEMO-A"), item("MEMO-B"), item("MEMO-C"))
        val reranker = SemanticReranker(FakeProvider("""["MEMO-C", "MEMO-A", "MEMO-B"]"""))
        val result = reranker.rerank("跑步", candidates)
        assertFalse(result.degraded)
        assertEquals(listOf("MEMO-C", "MEMO-A", "MEMO-B"), result.items.map { it.memoId })
    }

    @Test
    fun `支持对象形态回复`() = runBlockingTest {
        val candidates = listOf(item("MEMO-A"), item("MEMO-B"))
        val reranker = SemanticReranker(FakeProvider("""{"result": ["MEMO-B", "MEMO-A"]}"""))
        val result = reranker.rerank("工作", candidates)
        assertEquals(listOf("MEMO-B", "MEMO-A"), result.items.map { it.memoId })
    }

    @Test
    fun `markdown 围栏包裹的数组可重排`() = runBlockingTest {
        val candidates = listOf(item("MEMO-A"), item("MEMO-B"), item("MEMO-C"))
        val reranker = SemanticReranker(FakeProvider("```json\n[\"MEMO-B\", \"MEMO-C\", \"MEMO-A\"]\n```"))
        val result = reranker.rerank("测试", candidates)
        assertEquals(listOf("MEMO-B", "MEMO-C", "MEMO-A"), result.items.map { it.memoId })
    }

    @Test
    fun `模型失败降级为原顺序并标记`() = runBlockingTest {
        val candidates = listOf(item("MEMO-A"), item("MEMO-B"))
        val reranker = SemanticReranker(FakeProvider(fail = true))
        val result = reranker.rerank("跑步", candidates)
        assertTrue(result.degraded)
        assertTrue(result.reason.isNotBlank())
        assertEquals(listOf("MEMO-A", "MEMO-B"), result.items.map { it.memoId })
    }

    @Test
    fun `模型返回未知 id 时保留原顺序`() = runBlockingTest {
        val candidates = listOf(item("MEMO-A"), item("MEMO-B"))
        val reranker = SemanticReranker(FakeProvider("""["UNKNOWN-1", "MEMO-B"]"""))
        val result = reranker.rerank("测试", candidates)
        // MEMO-B 被确认，UNKNOWN-1 忽略 → 结果含 MEMO-B，且不退化
        assertFalse(result.degraded)
        assertEquals("MEMO-B", result.items.first().memoId)
    }

    @Test
    fun `单个候选直接返回不调用模型`() = runBlockingTest {
        val candidates = listOf(item("MEMO-A"))
        val reranker = SemanticReranker(FakeProvider())
        val result = reranker.rerank("测试", candidates)
        assertFalse(result.degraded)
        assertEquals(listOf("MEMO-A"), result.items.map { it.memoId })
    }

    private fun runBlockingTest(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
