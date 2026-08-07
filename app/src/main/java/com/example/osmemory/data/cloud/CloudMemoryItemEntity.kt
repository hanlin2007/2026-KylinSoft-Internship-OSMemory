package com.example.osmemory.data.cloud

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 云端树（Cloud Tree）原子记忆卡——模拟云端企业库 / 个人库（PPT 第 14 页 "Cloud Str"）。
 *
 * 与本地树完全隔离的独立表/独立数据库（osmemory_cloud.db），代表"云端侧"。
 * 单向数据流：仅由 TreeSyncManager 从本地树推送上来（云端可"拉取"本地）；
 * 本地树永不从本表拉数据（内网保密记忆隔离：本地不能 pull 云端内容）。
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

    /** 云端仅存放非敏感记忆（policyLevel=2 永不外发），此处恒为 0/1 */
    val policyLevel: Int = 1,

    val confidence: Float = 0.5f,
    val createdAt: Long,
    val updatedAt: Long,
    val evidenceRaw: String,

    /** 推送到云端的时间（毫秒） */
    val syncedAt: Long
)
