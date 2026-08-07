package com.example.osmemory.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.osmemory.data.db.dao.MemoryItemDao
import com.example.osmemory.data.db.dao.MemoryLogDao
import com.example.osmemory.data.db.dao.RegisteredAppDao
import com.example.osmemory.data.db.entity.MemoryItemEntity
import com.example.osmemory.data.db.entity.MemoryLogEntity
import com.example.osmemory.data.db.entity.RegisteredAppEntity

/**
 * OS Memory 记忆库（单例）——本地树（Local Tree）
 *
 * 对应 PPT：Atomic Store（图谱 + 向量索引）——阶段 1 先落地原子记忆卡表；
 * 阶段 1 修复：新增云同步字段（cloudEligible / syncState / syncedAt），支撑 Local/Cloud 双树单向同步。
 * 图谱边（memory_links）与向量索引在阶段 3/4 追加迁移。
 */
@Database(
    entities = [
        MemoryItemEntity::class,
        MemoryLogEntity::class,
        RegisteredAppEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class OSMemoryDatabase : RoomDatabase() {

    abstract fun memoryItemDao(): MemoryItemDao
    abstract fun memoryLogDao(): MemoryLogDao
    abstract fun registeredAppDao(): RegisteredAppDao

    companion object {
        @Volatile
        private var INSTANCE: OSMemoryDatabase? = null

        /** v1 → v2：本地记忆卡新增云同步三字段（历史库升级兼容） */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_items ADD COLUMN cloudEligible INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN syncState INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN syncedAt INTEGER")
            }
        }

        fun get(context: Context): OSMemoryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OSMemoryDatabase::class.java,
                    "osmemory.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
    }
}
