package com.vega.sting.storage

import android.content.Context
import android.util.Log
import com.vega.sting.database.AppDatabase
import com.vega.sting.repository.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object TrashRetentionManager {
    private const val TAG = "TrashRetentionManager"
    val RETENTION_PERIOD_MS: Long = TimeUnit.DAYS.toMillis(30)

    
    fun isExpired(deletedAt: Long, now: Long): Boolean {
        return deletedAt <= now - RETENTION_PERIOD_MS
    }

    
    fun computeCutoff(now: Long = System.currentTimeMillis()): Long {
        return now - RETENTION_PERIOD_MS
    }

    
    suspend fun cleanupOldTrash(context: Context): Int = withContext(Dispatchers.IO) {
        try {
            val database = AppDatabase.getDatabase(context)
            val dao = database.recordingDao()
            val repository = RecordingRepository(context, dao)

            val cutoffTime = computeCutoff()
            val expired = dao.getTrashDeletedBefore(cutoffTime)

            var deletedCount = 0
            for (recording in expired) {
                val file = File(recording.path)
                if (!file.exists()) {
                    
                    dao.delete(recording)
                    deletedCount++
                } else {
                    repository.permanentlyDeleteRecording(recording)
                    deletedCount++
                }
            }

            Log.d(TAG, "Cleaned up $deletedCount old items from trash.")
            deletedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old trash: ${e.message}", e)
            0
        }
    }
}
