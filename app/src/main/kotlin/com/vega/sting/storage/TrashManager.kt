package com.vega.sting.storage

import android.content.Context
import android.util.Log
import java.io.File

object TrashManager {
    private const val TAG = "TrashManager"
    private const val TRASH_DIR = ".trash"

    fun getTrashDirectory(
        context: Context,
        storageType: StorageType
    ): File {
        val root = StorageManager.getRecordingDirectory(
            context,
            storageType
        )
        val trash = File(root, TRASH_DIR)
        if (!trash.exists()) {
            trash.mkdirs()
        }
        return trash
    }

    fun moveToTrash(
        context: Context,
        sourceFile: File,
        storageType: StorageType
    ): File? {
        return try {
            val trashDir = getTrashDirectory(context, storageType)
            val trashFile = File(trashDir, sourceFile.name)

            if (sourceFile.renameTo(trashFile)) {
                return trashFile
            }

            // Fallback
            sourceFile.copyTo(trashFile, overwrite = true)
            sourceFile.delete()
            trashFile
        } catch (e: Exception) {
            Log.w(TAG, "moveToTrash failed for ${sourceFile.path}: ${e.message}")
            null
        }
    }

    fun restoreFromTrash(
        context: Context,
        trashFile: File,
        originalDir: File
    ): File? {
        return try {
            val restored = File(originalDir, trashFile.name)

            if (trashFile.renameTo(restored)) {
                return restored
            }

            // Fallback
            trashFile.copyTo(restored, overwrite = true)
            trashFile.delete()
            restored
        } catch (e: Exception) {
            Log.w(TAG, "restoreFromTrash failed for ${trashFile.path}: ${e.message}")
            null
        }
    }

    fun permanentlyDelete(file: File): Boolean {
        return try {
            if (!file.exists()) {
                // File already gone — the row can be cleaned up.
                Log.w(TAG, "permanentlyDelete: already missing ${file.path}")
                true
            } else {
                val ok = file.delete()
                if (!ok) Log.w(TAG, "permanentlyDelete: delete() returned false for ${file.path}")
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "permanentlyDelete failed for ${file.path}: ${e.message}")
            false
        }
    }
}
