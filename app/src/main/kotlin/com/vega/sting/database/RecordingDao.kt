package com.vega.sting.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    // In-progress segments (`segmentStatus = 'RECORDING'`) are deliberately
    // hidden from the library until they are finalised, matching the previous
    // behaviour where a row only appeared once its file was complete. The live
    // recording is represented by the synthetic row the UI adds itself.
    @Query("SELECT * FROM recordings WHERE deleted = 0 AND segmentStatus != 'RECORDING' ORDER BY timestamp DESC")
    fun getAllActiveRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE deleted = 0 AND segmentStatus != 'RECORDING'")
    suspend fun getActiveRecordingsSync(): List<Recording>

    @Query("SELECT * FROM recordings WHERE deleted = 1 ORDER BY timestamp DESC")
    fun getTrashRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE deleted = 1")
    suspend fun getTrashRecordingsSync(): List<Recording>


    @Query("SELECT * FROM recordings WHERE deleted = 1 AND deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun getTrashDeletedBefore(cutoff: Long): List<Recording>

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): Recording?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording)

    @Update
    suspend fun update(recording: Recording)


    @Query("UPDATE recordings SET deleted = 1, deletedAt = :deletedAt WHERE id = :id")
    suspend fun markDeleted(id: Int, deletedAt: Long)


    @Query("UPDATE recordings SET deleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun markRestored(id: Int)

    @Delete
    suspend fun delete(recording: Recording)

    // ---- Recording sessions / segmented parts -------------------------------

    /**
     * Inserts a segment unless a row for the same (sessionId, partIndex) already
     * exists. Returns the new row id, or -1 when the row was ignored, which
     * makes finalization idempotent across duplicate callbacks.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(recording: Recording): Long

    @Query("SELECT * FROM recordings WHERE sessionId = :sessionId ORDER BY partIndex ASC")
    suspend fun getSessionParts(sessionId: String): List<Recording>

    @Query("SELECT * FROM recordings WHERE sessionId = :sessionId ORDER BY partIndex ASC")
    fun observeSessionParts(sessionId: String): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE segmentStatus = 'RECORDING'")
    suspend fun getInProgressSegments(): List<Recording>

    @Query("SELECT path FROM recordings")
    suspend fun getAllPaths(): List<String>

    @Query(
        "UPDATE recordings SET name = :name, path = :path, size = :size, codec = :codec, " +
            "width = :width, height = :height, endedAt = :endedAt, " +
            "finalizationReason = :reason, segmentStatus = :status " +
            "WHERE sessionId = :sessionId AND partIndex = :partIndex"
    )
    suspend fun finalizeSegment(
        sessionId: String,
        partIndex: Int,
        name: String,
        path: String,
        size: Long,
        codec: String,
        width: Int,
        height: Int,
        endedAt: Long,
        reason: String,
        status: String
    )

    @Query("UPDATE recordings SET segmentStatus = :status, finalizationReason = :reason WHERE id = :id")
    suspend fun setSegmentStatus(id: Int, status: String, reason: String?)

    @Query("UPDATE recordings SET isSessionComplete = 1 WHERE sessionId = :sessionId AND segmentStatus != 'RECORDING'")
    suspend fun completeSession(sessionId: String)

    @Query("DELETE FROM recordings WHERE sessionId = :sessionId AND partIndex = :partIndex")
    suspend fun deleteSegment(sessionId: String, partIndex: Int)
}
