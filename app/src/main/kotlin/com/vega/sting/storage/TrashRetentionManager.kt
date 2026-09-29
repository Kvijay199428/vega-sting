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
    private val RETENTION_PERIOD_MS = TimeUnit.DAYS.toMillis(30) // 30 days

    suspend fun cleanupOldTrash(context: Context) = withContext(Dispatchers.IO) {
        try {
            val database = AppDatabase.getDatabase(context)
            val dao = database.recordingDao()
            val repository = RecordingRepository(context, dao)

            // Calculate cutoff time
            val cutoffTime = System.currentTimeMillis() - RETENTION_PERIOD_MS

            // Get all deleted recordings
            val deletedRecordings = dao.getTrashRecordingsSync()
            
            var deletedCount = 0
            
            for (recording in deletedRecordings) {
                // If the recording was deleted more than 30 days ago...
                // Currently, we don't store the exact deletion time, but we can check the 
                // file's last modified time if it's in the .trash directory.
                val file = File(recording.path)
                if (file.exists() && file.lastModified() < cutoffTime) {
                    repository.permanentlyDeleteRecording(recording)
                    deletedCount++
                }
            }
            
            Log.d(TAG, "Cleaned up $deletedCount old items from trash.")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old trash: ${e.message}", e)
        }
    }
}
