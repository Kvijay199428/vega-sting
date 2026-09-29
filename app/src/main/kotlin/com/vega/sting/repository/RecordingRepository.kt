package com.vega.sting.repository

import android.content.Context
import com.vega.sting.database.Recording
import com.vega.sting.database.RecordingDao
import com.vega.sting.storage.StorageManager
import com.vega.sting.storage.TrashManager
import java.io.File

class RecordingRepository(
    private val context: Context,
    private val recordingDao: RecordingDao
) {

    suspend fun softDeleteRecording(recording: Recording) {
        val sourceFile = File(recording.path)
        val storageType = StorageManager.detectStorageType(recording.path)
        val trashFile = TrashManager.moveToTrash(context, sourceFile, storageType)

        if (trashFile != null) {
            val updated = recording.copy(
                path = trashFile.absolutePath,
                originalPath = recording.path,
                deleted = true
            )
            recordingDao.update(updated)
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
                deleted = false
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
