package com.example.osmemory.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.osmemory.data.db.entity.MemoryLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryLogDao {

    @Insert
    suspend fun insert(log: MemoryLogEntity): Long

    /** 按类型观察日志（传入/检索/推理/安全敏感四板块） */
    @Query("SELECT * FROM memory_logs WHERE logType = :type ORDER BY timestamp DESC LIMIT :limit")
    fun observeByType(type: String, limit: Int = 500): Flow<List<MemoryLogEntity>>

    @Query("SELECT * FROM memory_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeAll(limit: Int = 500): Flow<List<MemoryLogEntity>>

    /** 全量日志（审计导出用，不做上限截断） */
    @Query("SELECT * FROM memory_logs ORDER BY timestamp DESC")
    suspend fun observeAllNow(): List<MemoryLogEntity>

    @Query("DELETE FROM memory_logs")
    suspend fun deleteAll()
}
