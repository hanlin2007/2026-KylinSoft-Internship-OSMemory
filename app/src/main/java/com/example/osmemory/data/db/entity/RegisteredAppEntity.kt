package com.example.osmemory.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 应用登记表（对应 PPT 第 10 页"应用层接入"与安全治理）
 *
 * 记忆安全原则：应用必须先登记才能读写记忆；
 * scope=WRITE 只能 memo_collect，scope=READ_WRITE 才可 get_memo。
 */
@Entity(tableName = "registered_apps")
data class RegisteredAppEntity(
    @PrimaryKey val appId: String,

    /** 应用显示名，如"记事本" */
    val appName: String,

    /** WRITE（只存） / READ_WRITE（读写） */
    val scope: String,

    val createdAt: Long = System.currentTimeMillis()
)
