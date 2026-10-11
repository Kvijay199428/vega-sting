package com.vega.sting.storage

import android.os.StatFs
import java.io.File
import java.util.Locale

/**
 * Thin, destination-aware reader for free space plus the point where the
 * [StoragePolicy] decision table is applied to a concrete directory.
 *
 * The measurements here are important to get right: a failure to read the
 * volume must never look like "lots of space". [snapshot] therefore returns an
 * explicit `available` flag instead of collapsing every failure to `0`.
 */
object StorageHealthManager {

    /** Retained for callers that still compare against a single warning level. */
    const val WARNING_LEVEL = StoragePolicy.DEFAULT_WARNING_BYTES

    /** Retained for callers that still compare against a single critical level. */
    val CRITICAL_LEVEL: Long = StoragePolicy.criticalBytes(StoragePolicy.DEFAULT_RESERVE_BYTES)

    /** A free-space reading that knows whether it can be trusted. */
    data class StorageSnapshot(
        val freeBytes: Long,
        val available: Boolean,
        val error: String? = null
    )

    /**
     * Reads the free space of the volume that actually contains [directory].
     *
     * Unlike a plain `StatFs` call, this distinguishes "the volume is gone or
     * not writable" and "the reading failed" from a genuine zero, so callers
     * never mistake a broken measurement for an empty-but-usable disk.
     */
    fun snapshot(directory: File): StorageSnapshot {
        return try {
            if (!directory.exists() && !directory.mkdirs()) {
                return StorageSnapshot(0L, false, "Recording directory is unavailable")
            }
            if (!directory.canWrite()) {
                return StorageSnapshot(0L, false, "Recording directory is not writable")
            }
            val stat = StatFs(directory.absolutePath)
            val free = stat.availableBytes
            if (free < 0L) {
                StorageSnapshot(0L, false, "Free space could not be verified")
            } else {
                StorageSnapshot(free, true)
            }
        } catch (e: Exception) {
            StorageSnapshot(0L, false, e.message ?: "Storage error")
        }
    }

    /**
     * Convenience overload: the raw free-space number, or `0` when it cannot be
     * read. Prefer [snapshot] when the difference between "empty" and "unknown"
     * matters.
     */
    fun getFreeSpaceBytes(directory: File): Long = snapshot(directory).freeBytes

    /** Applies the policy table to the volume containing [directory]. */
    fun getStorageStatus(
        directory: File,
        warningBytes: Long = StoragePolicy.DEFAULT_WARNING_BYTES,
        reserveBytes: Long = StoragePolicy.DEFAULT_RESERVE_BYTES
    ): StorageStatus {
        val snap = snapshot(directory)
        return StoragePolicy.statusFor(snap.freeBytes, warningBytes, reserveBytes, snap.available)
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
