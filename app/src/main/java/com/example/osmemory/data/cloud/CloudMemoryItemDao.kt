package com.example.osmemory.data.cloud

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 云端树（Cloud Tree）DAO——只读展示 + 单向写入（来自 TreeSyncManager）。
 *
 * 设计约束：本 DAO 不向本地树提供任何"读回"路径（本地不能 pull 云端内容）。
 */
@Dao
interface CloudMemoryItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CloudMemoryItemEntity)

    @Query("SELECT * FROM cloud_memory_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CloudMemoryItemEntity>>

    @Query("SELECT * FROM cloud_memory_items ORDER BY createdAt DESC")
    suspend fun all(): List<CloudMemoryItemEntity>

    @Query("SELECT COUNT(*) FROM cloud_memory_items")
    suspend fun count(): Int

    @Query("SELECT * FROM cloud_memory_items WHERE memoId = :memoId LIMIT 1")
    suspend fun byMemoId(memoId: String): CloudMemoryItemEntity?

    @Query("DELETE FROM cloud_memory_items")
    suspend fun deleteAll()
}
