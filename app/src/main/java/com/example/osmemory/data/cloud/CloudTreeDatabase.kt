package com.example.osmemory.data.cloud

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 云端树数据库（Cloud Tree）——独立 .db 文件模拟"云端"隔离存储。
 *
 * 与本地树（osmemory.db）物理分离：
 * - 本地树是 source of truth（本地优先、离线可用）
 * - 云端树是本地树的单向镜像（仅允许 本地→云端，云端不可反向回灌）
 *
 * 阶段 3/4 若接真实云端后端，可把本 DAO 替换为网络仓储实现，接口不变。
 */
@Database(
    entities = [CloudMemoryItemEntity::class],
    version = 2,
    exportSchema = false
)
abstract class CloudTreeDatabase : RoomDatabase() {

    abstract fun cloudMemoryItemDao(): CloudMemoryItemDao

    companion object {
        @Volatile
        private var INSTANCE: CloudTreeDatabase? = null

        /** v1 → v2：云端树 AutoDream 归档式遗忘两字段 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_memory_items ADD COLUMN dreamState INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cloud_memory_items ADD COLUMN mergedInto TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): CloudTreeDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CloudTreeDatabase::class.java,
                    "osmemory_cloud.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
    }
}
