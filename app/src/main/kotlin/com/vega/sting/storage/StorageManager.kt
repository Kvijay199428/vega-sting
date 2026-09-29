package com.vega.sting.storage

import android.content.Context
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

object StorageManager {

    private const val DIRECTORY_NAME = "VEGA STING"

    fun getRecordingDirectory(
        context: Context,
        storageType: StorageType
    ): File {

        val root = when (storageType) {

            StorageType.INTERNAL -> {
                Environment.getExternalStorageDirectory()
            }

            StorageType.SD_CARD -> {
                val dirs = ContextCompat.getExternalFilesDirs(context, null)
                if (dirs.size > 1 && dirs[1] != null) {
                    dirs[1]!!.parentFile?.parentFile?.parentFile?.parentFile ?: Environment.getExternalStorageDirectory()
                } else {
                    Environment.getExternalStorageDirectory()
                }
            }

            StorageType.OTG -> {
                val dirs = ContextCompat.getExternalFilesDirs(context, null)
                if (dirs.size > 2 && dirs.lastOrNull() != null) {
                    dirs.last()!!.parentFile?.parentFile?.parentFile?.parentFile ?: Environment.getExternalStorageDirectory()
                } else {
                    Environment.getExternalStorageDirectory()
                }
            }
        }

        val vegaDir = File(root, DIRECTORY_NAME)

        if (!vegaDir.exists()) {
            vegaDir.mkdirs()
        }

        return vegaDir
    }

    fun isOtgConnected(context: Context): Boolean {
        val dirs = ContextCompat.getExternalFilesDirs(context, null)
        return dirs.size > 2
    }

    fun getAvailableStorages(context: Context): List<Pair<StorageType, File>> {
        val storages = mutableListOf<Pair<StorageType, File>>()
        
        val internal = getRecordingDirectory(context, StorageType.INTERNAL)
        storages.add(StorageType.INTERNAL to internal)
        
        val dirs = ContextCompat.getExternalFilesDirs(context, null)
        
        if (dirs.size > 1 && dirs[1] != null) {
            val sd = getRecordingDirectory(context, StorageType.SD_CARD)
            storages.add(StorageType.SD_CARD to sd)
        }
        
        if (dirs.size > 2 && dirs.lastOrNull() != null) {
            val otg = getRecordingDirectory(context, StorageType.OTG)
            storages.add(StorageType.OTG to otg)
        }
        
        return storages
    }

    fun detectStorageType(path: String): StorageType {
        return when {
            path.contains("sd") -> StorageType.SD_CARD
            path.contains("usb") -> StorageType.OTG
            else -> StorageType.INTERNAL
        }
    }
}
