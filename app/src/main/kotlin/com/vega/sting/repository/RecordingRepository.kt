package com.vega.sting.repository

import android.content.Context
import android.util.Log
import com.vega.sting.database.Recording
import com.vega.sting.database.RecordingDao
import com.vega.sting.storage.StorageManager
import com.vega.sting.storage.TrashManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class RecordingRepository(
    private val context: Context,
    private val recordingDao: RecordingDao
) {
    companion object { private const val TAG = "RecordingRepository" }

    suspend fun softDeleteRecording(recording: Recording): Boolean =
        withContext(Dispatchers.IO) {
        val sourceFile = File(recording.path)
        val storageType = StorageManager.detectStorageType(recording.path)

        if (!sourceFile.exists()) {
            // Ghost row: the media file is already gone, so there is nothing
            // to move and nothing to preserve. Remove the DB row so it leaves
            // the list instead of silently failing every trash attempt.
            Log.w(TAG, "softDelete: source missing, removing row ${recording.id}: ${recording.path}")
            recordingDao.delete(recording)
            return@withContext true
        }

        val trashFile = TrashManager.moveToTrash(context, sourceFile, storageType)
        if (trashFile != null) {
            // Stamped once, here, so retention is measured from the trash action
            // rather than from when the file happened to be recorded.
            val deletedAt = System.currentTimeMillis()
            val updated = recording.copy(
                path = trashFile.absolutePath,
                originalPath = recording.path,
                deleted = true,
                deletedAt = deletedAt
            )
            recordingDao.update(updated)
            true
        } else {
            Log.w(TAG, "softDelete: failed to move ${recording.path}")
            false
        }
    }

    suspend fun restoreRecording(recording: Recording) {
        if (recording.originalPath == null) return

        val trashFile = File(recording.path)
        val originalFile = File(recording.originalPath)
        val originalDir = originalFile.parentFile ?: return

        val restoredFile = TrashManager.restoreFromTrash(context, trashFile, originalDir)

        if (restoredFile != null) {
            val updated = recording.copy(
                path = restoredFile.absolutePath,
                originalPath = null,
                deleted = false,
                deletedAt = null
            )
            recordingDao.update(updated)
        }
    }

    suspend fun permanentlyDeleteRecording(recording: Recording) {
        val file = File(recording.path)
        if (TrashManager.permanentlyDelete(file)) {
            recordingDao.delete(recording)
        }
    }
}
