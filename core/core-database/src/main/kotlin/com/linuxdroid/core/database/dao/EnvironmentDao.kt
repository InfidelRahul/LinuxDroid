package com.linuxdroid.core.database.dao

import androidx.room.*
import com.linuxdroid.core.database.entity.EnvironmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EnvironmentDao {

    @Query("SELECT * FROM environments ORDER BY created_at DESC")
    fun observeAll(): Flow<List<EnvironmentEntity>>

    @Query("SELECT * FROM environments WHERE id = :id")
    fun observeById(id: String): Flow<EnvironmentEntity?>

    @Query("SELECT * FROM environments WHERE id = :id")
    suspend fun getById(id: String): EnvironmentEntity?

    @Query("SELECT * FROM environments ORDER BY created_at DESC")
    suspend fun getAll(): List<EnvironmentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: EnvironmentEntity)

    @Update
    suspend fun update(entity: EnvironmentEntity)

    @Query("UPDATE environments SET state = :state, last_state_change_at = :timestamp, failure_message = :failureMessage WHERE id = :id")
    suspend fun updateState(id: String, state: String, timestamp: Long, failureMessage: String?)

    @Query("DELETE FROM environments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM environments")
    suspend fun count(): Int
}
