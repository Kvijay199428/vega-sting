package com.vega.sting.storage

import android.os.StatFs
import java.io.File
import java.util.Locale

object StorageHealthManager {

    const val WARNING_LEVEL = 1024L * 1024L * 1024L

    const val CRITICAL_LEVEL = 256L * 1024L * 1024L

    fun getFreeSpaceBytes(directory: File): Long {
        return try {
            val stat = StatFs(directory.absolutePath)
            stat.availableBytes
        } catch (e: Exception) {
            0L
        }
    }

    fun isLowStorage(directory: File): Boolean {
        return getFreeSpaceBytes(directory) <= WARNING_LEVEL
    }

    fun isCriticalStorage(directory: File): Boolean {
        return getFreeSpaceBytes(directory) <= CRITICAL_LEVEL
    }

    fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024f * 1024f * 1024f)
        return String.format("%.2f GB", gb)
    }

    
    fun formatFileSize(bytes: Long, locale: Locale? = null): String {
        val l = locale ?: Locale.getDefault()
        if (bytes < 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(l, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(l, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(l, "%.2f GB", gb)
    }
}
