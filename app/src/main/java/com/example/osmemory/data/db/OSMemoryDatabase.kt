package com.example.osmemory.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.osmemory.data.db.dao.MemoryItemDao
import com.example.osmemory.data.db.dao.MemoryLogDao
import com.example.osmemory.data.db.dao.RegisteredAppDao
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryLogEntity
import com.example.osmemory.data.db.entity.RegisteredAppEntity

/**
 * OS Memory 记忆库（单例）
 *
 * 对应 PPT：Atomic Store（图谱 + 向量索引）——阶段 1 先落地原子记忆卡表；
 * 图谱边（memory_links）与向量索引在阶段 3/4 追加迁移。
 */
@Database(
    entities = [
        MemoryItemEntity::class,
        MemoryLogEntity::class,
        RegisteredAppEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class OSMemoryDatabase : RoomDatabase() {

    abstract fun memoryItemDao(): MemoryItemDao
    abstract fun memoryLogDao(): MemoryLogDao
    abstract fun registeredAppDao(): RegisteredAppDao

    companion object {
        @Volatile
        private var INSTANCE: OSMemoryDatabase? = null

        fun get(context: Context): OSMemoryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OSMemoryDatabase::class.java,
                    "osmemory.db"
                ).build().also { INSTANCE = it }
            }
    }
}
