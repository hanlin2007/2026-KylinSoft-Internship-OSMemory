package com.example.osmemory.core.dream

import com.example.osmemory.data.cloud.CloudMemoryItemDao
import com.example.osmemory.data.cloud.CloudMemoryItemEntity
import com.example.osmemory.data.db.dao.MemoryItemDao
import com.example.osmemory.data.db.dao.MemoryLogDao
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryLogEntity
import com.example.osmemory.core.pipeline.MemoryPipeline.Companion.LOG_SECURITY
import com.example.osmemory.core.pipeline.MemoryPipeline.Companion.SYNC_PENDING
import com.example.osmemory.core.model.JsonTools

/**
 * 本地树 TreeOps：Dream 引擎读写本地树（端侧算力整合的目标树）。
 * 归档 = dreamState=2 + mergedInto 指向吞并者（不物理删除，可恢复）。
 * 敏感记忆被普通记忆覆盖时留 SECURITY 日志（安全基线留痕）。
 */
class LocalTreeOps(
    private val itemDao: MemoryItemDao,
    private val logDao: MemoryLogDao? = null
) : TreeOps {

    override val tree: String = "LOCAL"

    override suspend fun allActive(): List<DreamItem> =
        itemDao.allItems()
            .filter { it.dreamState == DreamItem.STATE_ACTIVE }
            .map(DreamItem::fromLocal)

    override suspend fun allArchived(): List<DreamItem> =
        itemDao.allItems()
            .filter { it.dreamState == DreamItem.STATE_ARCHIVED }
            .map(DreamItem::fromLocal)

    override suspend fun update(item: DreamItem) {
        val entity = itemDao.byMemoId(item.memoId) ?: return
        itemDao.update(
            entity.copy(
                confidence = item.confidence,
                evidenceRaw = item.evidenceRaw,
                updatedAt = item.updatedAt,
                syncState = if (entity.cloudEligible && entity.policyLevel < 2) SYNC_PENDING else entity.syncState
            )
        )
    }

    override suspend fun insert(item: DreamItem): String {
        val entity = MemoryItemEntity(
            memoId = item.memoId,
            contentHash = com.example.osmemory.core.model.TextTools.normalizeHash(item.content),
            content = item.content,
            title = item.title,
            category = item.category,
            tags = item.tags,
            source = item.source,
            appId = "osmemory_console",
            policyLevel = item.policyLevel,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt,
            confidence = item.confidence,
            evidenceRaw = item.evidenceRaw,
            cloudEligible = item.policyLevel < 2,
            syncState = if (item.policyLevel < 2) 1 else 0
        )
        itemDao.insert(entity)
        return item.memoId
    }

    override suspend fun archive(item: DreamItem, mergedInto: String) {
        val entity = itemDao.byMemoId(item.memoId) ?: return
        val wasSensitive = entity.policyLevel >= 2
        itemDao.update(
            entity.copy(
                dreamState = DreamItem.STATE_ARCHIVED,
                mergedInto = mergedInto,
                updatedAt = System.currentTimeMillis(),
                syncState = if (entity.cloudEligible && entity.policyLevel < 2) SYNC_PENDING else entity.syncState
            )
        )
        // 安全基线留痕：敏感记忆被整合吞并（含被普通记忆覆盖的冲突场景）
        if (wasSensitive) {
            logDao?.insert(
                MemoryLogEntity(
                    logType = LOG_SECURITY,
                    action = "dream_archive_sensitive",
                    appId = "osmemory_console",
                    memoIds = entity.memoId,
                    timestamp = System.currentTimeMillis(),
                    source = "system",
                    contentSummary = "Dream 归档敏感记忆：${entity.title}（并入 ${mergedInto.ifBlank { "拆分" }}）",
                    tags = "敏感",
                    extra = JsonTools.buildJson("mergedInto" to mergedInto)
                )
            )
        }
    }

    // ---------- 不活跃遗忘（TreeOps 实现） ----------

    override suspend fun lastDreamTimestamp(): Long {
        return logDao?.lastTimestamp("DREAM") ?: 0L
    }

    override suspend fun incrementInactiveCycles(lastDreamAt: Long) {
        itemDao.incrementInactiveCycles(lastDreamAt)
    }

    override suspend fun staleMemoIds(minCycles: Int): List<String> {
        return itemDao.allItems()
            .filter { it.dreamState == DreamItem.STATE_ACTIVE && it.inactiveDreamCycles >= minCycles }
            .map { it.memoId }
    }

    override suspend fun deleteStaleInactive(): Int {
        return itemDao.deleteStaleInactive()
    }
}

/** 云端树 TreeOps：Dream 引擎读写云端树（云端算力，整合结果不脱离云端树） */
class CloudTreeOps(
    private val cloudDao: CloudMemoryItemDao,
    private val logDao: MemoryLogDao? = null
) : TreeOps {

    override val tree: String = "CLOUD"

    override suspend fun allActive(): List<DreamItem> =
        cloudDao.all()
            .filter { it.dreamState == DreamItem.STATE_ACTIVE }
            .map(DreamItem::fromCloud)

    override suspend fun allArchived(): List<DreamItem> =
        cloudDao.all()
            .filter { it.dreamState == DreamItem.STATE_ARCHIVED }
            .map(DreamItem::fromCloud)

    override suspend fun update(item: DreamItem) {
        val entity = cloudDao.byMemoId(item.memoId) ?: return
        cloudDao.update(
            entity.copy(
                confidence = item.confidence,
                evidenceRaw = item.evidenceRaw,
                updatedAt = item.updatedAt
            )
        )
    }

    override suspend fun insert(item: DreamItem): String {
        cloudDao.upsert(
            CloudMemoryItemEntity(
                memoId = item.memoId,
                content = item.content,
                title = item.title,
                category = item.category,
                tags = item.tags,
                source = item.source,
                appId = "osmemory_console",
                policyLevel = item.policyLevel,
                confidence = item.confidence,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
                evidenceRaw = item.evidenceRaw,
                syncedAt = System.currentTimeMillis(),
                dreamState = item.dreamState,
                mergedInto = item.mergedInto
            )
        )
        return item.memoId
    }

    override suspend fun archive(item: DreamItem, mergedInto: String) {
        val entity = cloudDao.byMemoId(item.memoId) ?: return
        val wasSensitive = entity.policyLevel >= 2
        cloudDao.update(
            entity.copy(
                dreamState = DreamItem.STATE_ARCHIVED,
                mergedInto = mergedInto,
                updatedAt = System.currentTimeMillis()
            )
        )
        if (wasSensitive) {
            logDao?.insert(
                MemoryLogEntity(
                    logType = LOG_SECURITY,
                    action = "dream_archive_sensitive",
                    appId = "osmemory_console",
                    memoIds = entity.memoId,
                    timestamp = System.currentTimeMillis(),
                    source = "cloud",
                    contentSummary = "云端 Dream 归档敏感记忆：${entity.title}（并入 ${mergedInto.ifBlank { "拆分" }}）",
                    tags = "敏感",
                    extra = JsonTools.buildJson("mergedInto" to mergedInto)
                )
            )
        }
    }

    // 云端树不做不活跃遗忘（云端是演示缓存，遗忘只对本地树生效）
    override suspend fun lastDreamTimestamp(): Long = 0L
    override suspend fun incrementInactiveCycles(lastDreamAt: Long) {}
    override suspend fun staleMemoIds(minCycles: Int): List<String> = emptyList()
    override suspend fun deleteStaleInactive(): Int = 0
}
