package com.example.osmemory.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 记忆调用日志（对应开发方案"日志三板块"）
 *
 * logType：
 *  - COLLECT  记忆传入：每条记忆进来时留一条（时间/来源/内容/标签）
 *  - RETRIEVE 记忆检索：记忆被应用调用时留一条（查询/命中）
 *  - INFER    记忆推理：模型推理场景（结构化抽取/画像/整合）留一条
 */
@Entity(
    tableName = "memory_logs",
    indices = [Index(value = ["logType", "timestamp"])]
)
data class MemoryLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** COLLECT / RETRIEVE / INFER */
    val logType: String,

    /** 动作：add / update / extract / rank / profile / autodream / seed / reject... */
    val action: String,

    /** 来源应用标识 */
    val appId: String,

    /** 涉及记忆（逗号分隔 memoId 列表，可为空） */
    val memoIds: String = "",

    /** 格式化字段：发生时间（毫秒） */
    val timestamp: Long,

    /** 格式化字段：来源（console / notes / chat / files / demo） */
    val source: String,

    /** 格式化字段：内容摘要（截断的原文或模型摘要） */
    val contentSummary: String,

    /** 格式化字段：标签（逗号分隔） */
    val tags: String = "",

    /** 附加 JSON（如 LLM 原始响应摘要、敏感命中词、降级标记） */
    val extra: String = "{}"
)
