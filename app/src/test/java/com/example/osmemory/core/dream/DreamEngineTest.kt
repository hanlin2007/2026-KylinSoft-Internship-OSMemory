package com.example.osmemory.core.dream

import com.example.osmemory.core.model.ModelException
import com.example.osmemory.core.model.ModelProvider
import com.example.osmemory.data.db.dao.MemoryLogDao
import com.example.osmemory.data.db.entity.MemoryLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DreamEngine 四步流水线集成测试（fake 树/模型/日志）：
 * 冲突消解（LLM + 规则兜底）→ 原子拆分 → 去重合并 → 高维提炼。
 * 断言归档式遗忘（不删除、可恢复）与永不抛出（降级报告）。
 */
class DreamEngineTest {

    /** 假模型：按调用顺序弹出回复；fail 时抛异常（模拟离线/网关失败） */
    private class FakeProvider(
        private val fail: Boolean = false,
        private val replies: List<String> = emptyList()
    ) : ModelProvider {
        override val name = "fake"
        private var callCount = 0

        override suspend fun complete(system: String, user: String, temperature: Double): String {
            if (fail) throw ModelException("模型网关不可达")
            val idx = callCount++
            return replies.getOrElse(idx) { "" }
        }
    }

    /** 假日志 DAO：只记录插入条数 */
    private class FakeLogDao : MemoryLogDao {
        var insertCount = 0

        override suspend fun insert(log: MemoryLogEntity): Long {
            insertCount++
            return 0L
        }

        override fun observeByType(type: String, limit: Int): Flow<List<MemoryLogEntity>> = flowOf(emptyList())
        override fun observeAll(limit: Int): Flow<List<MemoryLogEntity>> = flowOf(emptyList())
        override suspend fun observeAllNow(): List<MemoryLogEntity> = emptyList()
        override suspend fun deleteAll() = Unit
    }

    /** 假树：内存列表 + 操作记录（归档置状态而非删除，模拟可恢复） */
    private class FakeTreeOps(initial: List<DreamItem>) : TreeOps {
        override val tree = "LOCAL"
        val items = initial.toMutableList()
        val archived = mutableListOf<Pair<Long, String>>()
        val inserted = mutableListOf<DreamItem>()
        val updated = mutableListOf<DreamItem>()

        override suspend fun allActive() = items.filter { it.dreamState == DreamItem.STATE_ACTIVE }
        override suspend fun allArchived() = items.filter { it.dreamState == DreamItem.STATE_ARCHIVED }

        override suspend fun update(item: DreamItem) {
            updated.add(item)
        }

        override suspend fun insert(item: DreamItem): String {
            inserted.add(item)
            return item.memoId
        }

        override suspend fun archive(itemId: Long, mergedInto: String) {
            archived.add(itemId to mergedInto)
            val idx = items.indexOfFirst { it.id == itemId }
            if (idx >= 0) items[idx] = items[idx].copy(
                dreamState = DreamItem.STATE_ARCHIVED, mergedInto = mergedInto
            )
        }
    }

    private fun item(
        id: Long,
        memoId: String,
        content: String,
        category: String = "运动",
        tags: String = "跑步",
        updatedAt: Long = 100,
        confidence: Float = 0.8f
    ) = DreamItem(
        id = id, memoId = memoId, content = content, title = memoId, category = category,
        tags = tags, source = "console", policyLevel = 0, confidence = confidence,
        createdAt = 100, updatedAt = updatedAt, evidenceRaw = content
    )

    // ---------- 空库 / 稳定库 ----------

    @Test
    fun `空库不整合返回保持原状`() = runBlockingTest {
        val engine = DreamEngine(FakeProvider(), FakeTreeOps(emptyList()), FakeLogDao())
        val report = engine.dream(online = false)

        assertFalse(report.changed)
        assertFalse(report.degraded)
        assertTrue(report.message.contains("无需整合"))
        assertEquals("LOCAL", report.tree)
    }

    // ---------- ① 冲突消解 ----------

    @Test
    fun `规则兜底消解极性冲突并归档旧记忆`() = runBlockingTest {
        // 模型不可用 → 情感极性规则判定：喜欢 vs 讨厌 → 后写入优先
        val tree = FakeTreeOps(listOf(
            item(1, "MEMO-1", "我喜欢跑步", updatedAt = 100),
            item(2, "MEMO-2", "我讨厌跑步", updatedAt = 200)
        ))
        val engine = DreamEngine(FakeProvider(fail = true), tree, FakeLogDao())

        val report = engine.dream(online = false)

        assertEquals(1, report.conflictsResolved)
        assertEquals(1, report.archivedCount)
        assertTrue(report.changed)
        // 旧记忆被归档且指向胜者（可恢复，不删除）
        assertEquals(listOf(1L to "MEMO-2"), tree.archived)
        assertTrue(tree.allActive().any { it.memoId == "MEMO-2" })
    }

    @Test
    fun `LLM 判定冲突对按后写入优先归档`() = runBlockingTest {
        val tree = FakeTreeOps(listOf(
            item(1, "MEMO-1", "我支持晚上跑步", updatedAt = 100),
            item(2, "MEMO-2", "我不再支持晚上跑步", updatedAt = 200)
        ))
        val engine = DreamEngine(
            FakeProvider(replies = listOf("""{"conflicts":[{"index":0,"reason":"前后矛盾"}]}""")),
            tree,
            FakeLogDao()
        )

        val report = engine.dream(online = false)

        assertEquals(1, report.conflictsResolved)
        assertEquals(1, report.archivedCount)
        assertFalse(report.degraded)
        assertTrue(report.message.contains("冲突消解"))
    }

    @Test
    fun `敏感记忆不可被普通记忆覆盖`() = runBlockingTest {
        // 普通新记忆"不再服药" vs 敏感旧记忆"按时服药"：敏感保留，普通记忆归档
        val sensitive = item(1, "MEMO-S", "我按时服用降压药", updatedAt = 100).copy(policyLevel = 2)
        val normal = item(2, "MEMO-N", "我不再服用降压药", updatedAt = 200)
        val tree = FakeTreeOps(listOf(sensitive, normal))
        val engine = DreamEngine(
            FakeProvider(replies = listOf("""{"conflicts":[{"index":0,"reason":"矛盾"}]}""")),
            tree,
            FakeLogDao()
        )

        val report = engine.dream(online = false)

        assertEquals(1, report.conflictsResolved)
        // 归档的是普通记忆，敏感记忆保持活跃
        assertEquals(listOf(2L to "MEMO-S"), tree.archived)
        assertTrue(tree.allActive().any { it.memoId == "MEMO-S" })
    }

    // ---------- ② 原子拆分 ----------

    @Test
    fun `长复合记忆按句读拆分原记忆归档`() = runBlockingTest {
        // 112 字符、含 3 个句读 → 触发原子拆分（超过 SPLIT_MIN_LENGTH=80）
        val longContent = "我每天早上七点起床出门去公园慢跑三十分钟然后回家做拉伸运动，之后再吃早餐看新闻整理今天的计划。晚上九点会再走二十分钟散步放松心情然后看书半小时。周末通常去爬山或者游泳每次至少两个小时，回来之后会准备下周的工作计划并采购食材。"
        assertTrue("长文本应超过拆分阈值：${longContent.length}", longContent.length > DreamEngine.SPLIT_MIN_LENGTH)
        val tree = FakeTreeOps(listOf(item(1, "MEMO-L", longContent)))
        // 模型不可用 → 规则拆分
        val engine = DreamEngine(FakeProvider(fail = true), tree, FakeLogDao())

        val report = engine.dream(online = false)

        assertTrue("应拆出至少 2 条：${report.splitCount}", report.splitCount >= 2)
        assertTrue(report.changed)
        // 原复合记忆归档（保留，可恢复）
        assertEquals(listOf(1L to ""), tree.archived)
        // 产物全部带 dream_split 来源标记（可追溯）
        assertTrue(tree.inserted.isNotEmpty())
        assertTrue(tree.inserted.all { it.source == "dream_split" })
        assertTrue(tree.inserted.all { it.content.length >= 4 })
    }

    // ---------- ③ 去重合并 ----------

    @Test
    fun `标点差异近似重复合并保留证据`() = runBlockingTest {
        val tree = FakeTreeOps(listOf(
            item(1, "MEMO-1", "我每天早上七点起床跑步。", updatedAt = 200, confidence = 0.9f),
            item(2, "MEMO-2", "我每天早上七点起床跑步", updatedAt = 100, confidence = 0.7f)
        ))
        val engine = DreamEngine(FakeProvider(fail = true), tree, FakeLogDao())

        val report = engine.dream(online = false)

        assertEquals(1, report.mergedCount)
        assertEquals(1, report.archivedCount)
        assertTrue(report.changed)
        // 被并者（旧、低置信）归档指向胜者
        assertEquals(listOf(2L to "MEMO-1"), tree.archived)
        // 胜者更新：置信度取高、证据拼接
        assertEquals(1, tree.updated.size)
        assertEquals(0.9f, tree.updated[0].confidence, 0f)
        assertTrue(tree.updated[0].evidenceRaw.contains("每天早上七点起床跑步"))
    }

    // ---------- ④ 高维提炼 ----------

    @Test
    fun `LLM 提炼高维记忆并继承最高敏感级`() = runBlockingTest {
        val tree = FakeTreeOps(listOf(
            item(1, "MEMO-1", "我喜欢跑步三十分钟", updatedAt = 100),
            item(2, "MEMO-2", "我喜欢游泳一小时", updatedAt = 100)
        ))
        // 调用顺序：冲突判定（无冲突）→ 蒸馏（产出高维记忆）
        val engine = DreamEngine(
            FakeProvider(replies = listOf(
                """{"conflicts":[]}""",
                """{"distilled":"用户热爱运动健身","basis":"跑步与游泳偏好"}"""
            )),
            tree,
            FakeLogDao()
        )

        val report = engine.dream(online = false)

        assertEquals(1, report.distilledCount)
        assertTrue(report.changed)
        val distilled = tree.inserted.single()
        assertEquals("dream_distill", distilled.source)
        assertEquals("用户热爱运动健身", distilled.content)
        assertTrue(distilled.tags.contains("高维"))
        assertTrue(distilled.evidenceRaw.contains("MEMO-1"))
    }

    @Test
    fun `模型失败时提炼不臆造不产生产物`() = runBlockingTest {
        val tree = FakeTreeOps(listOf(
            item(1, "MEMO-1", "我喜欢跑步三十分钟", updatedAt = 100),
            item(2, "MEMO-2", "我喜欢游泳一小时", updatedAt = 100)
        ))
        val engine = DreamEngine(FakeProvider(fail = true), tree, FakeLogDao())

        val report = engine.dream(online = false)

        assertEquals(0, report.distilledCount)
        assertEquals(0, report.archivedCount)
        assertFalse(report.changed)
    }

    // ---------- 稳健性 ----------

    @Test
    fun `模型全面失败引擎永不抛出并照常完成规则兜底`() = runBlockingTest {
        // 长文本 → 规则拆分产出；极性相反 → 规则冲突判定也走一遍（此处极性 0 不冲突）
        val longContent = "我每天早上七点起床出门去公园慢跑三十分钟然后回家做拉伸运动，之后再吃早餐看新闻整理今天的计划。晚上九点会再走二十分钟散步放松心情然后看书半小时。周末通常去爬山或者游泳每次至少两个小时，回来之后会准备下周的工作计划并采购食材。"
        val tree = FakeTreeOps(listOf(
            item(1, "MEMO-1", longContent, updatedAt = 100),
            item(2, "MEMO-2", "我讨厌跑步", updatedAt = 200)
        ))
        val engine = DreamEngine(FakeProvider(fail = true), tree, FakeLogDao())

        // 不应抛出；规则兜底照常完成（拆分产出 3 条原子记忆）
        val report = engine.dream(online = false)

        assertFalse(report.degraded)
        assertTrue("规则兜底应有产出：splitCount=${report.splitCount}", report.splitCount >= 2)
        assertTrue(report.conflictsResolved + report.splitCount + report.mergedCount > 0)
    }

    @Test
    fun `每次 Dream 都写入 DREAM 日志`() = runBlockingTest {
        val logDao = FakeLogDao()
        val engine = DreamEngine(
            FakeProvider(replies = listOf("""{"conflicts":[]}""", """{"distilled":null}""")),
            FakeTreeOps(listOf(item(1, "MEMO-1", "我喜欢跑步", updatedAt = 100))),
            logDao
        )

        engine.dream(online = true)

        assertEquals(1, logDao.insertCount)
    }

    private fun runBlockingTest(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
