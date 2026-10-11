package com.vega.sting.settings

import com.vega.sting.storage.StoragePolicy

/**
 * Snapshot of the file-size and storage-safety preferences.
 *
 * The recording service reads this once at session start and then holds it for
 * the whole session, so changing the limit mid-recording cannot move the goal
 * posts for the file that is currently being written.
 */
data class RecordingSettings(
    val splitRecordingEnabled: Boolean,
    val videoSegmentLimitBytes: Long,
    val audioSegmentLimitBytes: Long,
    val minimumFreeSpaceBytes: Long,
    val lowStorageWarningBytes: Long,
    val audioFallbackEnabled: Boolean
) {
    companion object {
        val DEFAULTS = RecordingSettings(
            splitRecordingEnabled = true,
            videoSegmentLimitBytes = 1024L * 1024L * 1024L,   // 1 GB
            audioSegmentLimitBytes = 100L * 1024L * 1024L,    // 100 MB
            minimumFreeSpaceBytes = StoragePolicy.DEFAULT_RESERVE_BYTES,
            lowStorageWarningBytes = StoragePolicy.DEFAULT_WARNING_BYTES,
            audioFallbackEnabled = true
        )

        /** Applies the safety bounds to arbitrary stored values. */
        fun sanitize(settings: RecordingSettings): RecordingSettings = RecordingSettings(
            splitRecordingEnabled = settings.splitRecordingEnabled,
            videoSegmentLimitBytes = StoragePolicy.clampSegmentLimit(settings.videoSegmentLimitBytes),
            audioSegmentLimitBytes = StoragePolicy.clampSegmentLimit(settings.audioSegmentLimitBytes),
            minimumFreeSpaceBytes = StoragePolicy.clampReserve(settings.minimumFreeSpaceBytes),
            lowStorageWarningBytes = settings.lowStorageWarningBytes
                .coerceAtLeast(StoragePolicy.MIN_RESERVE_BYTES),
            audioFallbackEnabled = settings.audioFallbackEnabled
        )
    }
}
