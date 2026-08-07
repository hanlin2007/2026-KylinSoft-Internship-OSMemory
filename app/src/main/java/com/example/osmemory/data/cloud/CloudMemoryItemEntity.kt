package com.example.osmemory.data.cloud

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 云端树（Cloud Tree）原子记忆卡——模拟内网企业/政企云端库（PPT 第 14 页 "Cloud Str"）。
 *
 * 与本地树完全隔离的独立表/独立数据库（osmemory_cloud.db），代表"云端侧"。
 * 单向数据流：仅由 TreeSyncManager 从本地树拉取上来（云端可"拉取"本地）；
 * 本地树永不从本表拉数据（内网保密记忆隔离：本地不能 pull 云端内容）。
 *
 * 来源两分（阶段 2 修复 v2，对齐验收意见）：
 *  - [ORIGIN_LOCAL_SYNC]：来自本地同步（保留原记忆的敏感判断，policyLevel 原值带入）；
 *  - [ORIGIN_CLOUD_CREATE]：在云端/内网环境中创建（与本地同一套 SecurityGate + LLM 敏感判断）。
 * 敏感判断与本地树完全一致（不再一律标敏感）；断网后云端树锁定不可查看、不可离线。
 */
@Entity(
    tableName = "cloud_memory_items",
    indices = [Index(value = ["memoId"], unique = true)]
)
data class CloudMemoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 与本地树一致的稳定 memoId（可跨树追溯来源） */
    val memoId: String,

    val content: String,
    val title: String,
    val category: String,
    val tags: String,
    val source: String,
    val appId: String,

    /** 敏感判断与本地一致：0 公开 / 1 普通 / 2 敏感（本地同步带入原值，云端创建按内容判断） */
    val policyLevel: Int = 1,

    val confidence: Float = 0.5f,
    val createdAt: Long,
    val updatedAt: Long,
    val evidenceRaw: String,

    /** 推送到云端的时间（毫秒） */
    val syncedAt: Long
) {
    companion object {
        /** 来源：来自本地同步（memoId 沿用本地原值） */
        const val ORIGIN_LOCAL_SYNC = "LOCAL_SYNC"

        /** 来源：在云端/内网环境中创建（memoId 带 MEMO-CLOUD- 前缀） */
        const val ORIGIN_CLOUD_CREATE = "CLOUD_CREATE"

        /** 云端创建的唯一入口 memoId 前缀（见 TreeSyncManager.addToCloud） */
        private const val CLOUD_CREATE_PREFIX = "MEMO-CLOUD-"
    }

    /** 来源场景（计算属性，不落库）：本地同步 / 云端创建 */
    val origin: String
        get() = if (memoId.startsWith(CLOUD_CREATE_PREFIX)) ORIGIN_CLOUD_CREATE else ORIGIN_LOCAL_SYNC
}
