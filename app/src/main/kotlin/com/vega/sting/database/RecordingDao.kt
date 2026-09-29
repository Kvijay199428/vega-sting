package com.vega.sting.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings WHERE deleted = 0 ORDER BY timestamp DESC")
    fun getAllActiveRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE deleted = 1 ORDER BY timestamp DESC")
    fun getTrashRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE deleted = 1")
    suspend fun getTrashRecordingsSync(): List<Recording>

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): Recording?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording)

    @Update
    suspend fun update(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording)
}
