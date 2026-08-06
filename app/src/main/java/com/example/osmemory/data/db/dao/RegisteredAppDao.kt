package com.example.osmemory.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.osmemory.data.db.entity.RegisteredAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RegisteredAppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: RegisteredAppEntity)

    @Query("SELECT * FROM registered_apps ORDER BY createdAt")
    fun observeAll(): Flow<List<RegisteredAppEntity>>

    @Query("SELECT * FROM registered_apps WHERE appId = :appId LIMIT 1")
    suspend fun byId(appId: String): RegisteredAppEntity?
}
