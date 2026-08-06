package com.example.osmemory.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.osmemory.data.db.entity.MemoryItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryItemDao {

    @Insert
    suspend fun insert(item: MemoryItemEntity): Long

    @Update
    suspend fun update(item: MemoryItemEntity)

    @Delete
    suspend fun delete(item: MemoryItemEntity)

    @Query("SELECT * FROM memory_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_items WHERE memoId = :memoId LIMIT 1")
    suspend fun byMemoId(memoId: String): MemoryItemEntity?

    @Query("SELECT COUNT(*) FROM memory_items")
    suspend fun count(): Int

    @Query("DELETE FROM memory_items")
    suspend fun deleteAll()

    /** 关键词召回（阶段 1 基础检索）：匹配 content / tags / category / title */
    @Query(
        """
        SELECT * FROM memory_items
        WHERE content LIKE '%' || :keyword || '%'
           OR tags LIKE '%' || :keyword || '%'
           OR category LIKE '%' || :keyword || '%'
           OR title LIKE '%' || :keyword || '%'
        ORDER BY createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun keywordSearch(keyword: String, limit: Int): List<MemoryItemEntity>

    /** 复用频率自增（检索命中时调用） */
    @Query("UPDATE memory_items SET reuseCount = reuseCount + 1 WHERE id = :id")
    suspend fun bumpReuseCount(id: Long)

    /** 去重查询：同源同哈希且创建时间在窗口内的记忆（窗口外允许再次记忆） */
    @Query(
        """
        SELECT * FROM memory_items
        WHERE contentHash = :hash AND source = :source AND createdAt >= :since
        ORDER BY createdAt DESC LIMIT 1
        """
    )
    suspend fun findByHash(hash: String, source: String, since: Long): MemoryItemEntity?
}
