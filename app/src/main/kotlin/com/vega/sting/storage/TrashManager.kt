package com.vega.sting.storage

import android.content.Context
import java.io.File

object TrashManager {

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
            e.printStackTrace()
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
            e.printStackTrace()
            null
        }
    }

    fun permanentlyDelete(file: File): Boolean {
        return try {
            file.delete()
        } catch (e: Exception) {
            false
        }
    }
}
