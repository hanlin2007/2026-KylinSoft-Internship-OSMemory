package com.example.osmemory.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 原子记忆卡（对应 PPT 第 9 页 Memory Item Card）
 *
 * 字段对齐 PPT：来源 Source / 内容 Content / 权限 Policy / 时间 Time /
 * 证据 Evidence / 质量 Quality / 连接 Links。
 */
@Entity(
    tableName = "memory_items",
    indices = [
        Index(value = ["memoId"], unique = true),
        Index(value = ["contentHash"])
    ]
)
data class MemoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 对外稳定标识，如 MEMO-1754390000000-a1b2c3 */
    val memoId: String,

    /** 归一化内容哈希（去重用：空白/大小写归一后取哈希） */
    val contentHash: String,

    /** 内容 Content：可解释自然语言，已净化 */
    val content: String,

    /** LLM 抽取的一句话标题 */
    val title: String,

    /** 封闭分类：用户画像/日程事件/项目上下文/偏好风格/任务轨迹/联系人关系/地点设备/其他 */
    val category: String,

    /** LLM 遴选标签，逗号分隔 */
    val tags: String,

    /** 来源 Source：console / notes / chat / files / demo */
    val source: String,

    /** 来源应用标识（对应 registered_apps.appId） */
    val appId: String,

    /** 权限 Policy 可见范围，默认 user */
    val visibility: String = "user",

    /** 权限 Policy 敏感等级：0=公开 1=普通 2=敏感（安全门控输出依据） */
    val policyLevel: Int = 1,

    /** 时间 Time：生成时间（毫秒） */
    val createdAt: Long,

    /** 时间 Time：更新时间（毫秒） */
    val updatedAt: Long,

    /** 时间 Time：过期时间（毫秒，null 表示长期） */
    val expiresAt: Long? = null,

    /** 质量 Quality：置信度 0~1 */
    val confidence: Float = 0.5f,

    /** 质量 Quality：冲突状态 0=无冲突 1=疑似冲突 2=已整合（AutoDream 使用） */
    val conflictState: Int = 0,

    /** 质量 Quality：复用频率（检索命中自增） */
    val reuseCount: Int = 0,

    /** 证据 Evidence：原始片段（可追溯） */
    val evidenceRaw: String,

    /** 证据 Evidence：是否经用户确认 */
    val evidenceConfirmed: Boolean = false,

    /** 连接 Links：关联记忆/任务/实体的 JSON（阶段 2 图谱使用） */
    val links: String = "{}"
)
