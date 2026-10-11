package com.vega.sting.storage

/**
 * Single source of truth for storage-safety decisions.
 *
 * This object is deliberately free of Android imports so the decision table can
 * be unit-tested on the JVM. The actual `StatFs` read lives in
 * [StorageHealthManager]; this class only turns a free-space number into a
 * policy answer.
 *
 * File splitting and storage protection are separate concerns:
 *  - [shouldRotateFile] answers "has this file reached its size limit?"
 *  - [shouldStopRecording] answers "is the volume out of safe space for ALL files?"
 */
object StoragePolicy {

    /** Default warning level: below this the user is warned. */
    const val DEFAULT_WARNING_BYTES: Long = 1024L * 1024L * 1024L

    /** Default hard reserve: at or below this all recording stops. */
    const val DEFAULT_RESERVE_BYTES: Long = 100L * 1024L * 1024L

    /** Extra headroom an audio fallback must leave above the reserve. */
    const val DEFAULT_AUDIO_FALLBACK_MARGIN_BYTES: Long = 50L * 1024L * 1024L

    /**
     * Video stops this far above the reserve so that MP4 metadata and file
     * finalization still have writable space even at the boundary.
     */
    const val VIDEO_SAFETY_HEADROOM_BYTES: Long = 150L * 1024L * 1024L

    /** Bounds applied to user-configured reserve values. */
    const val MIN_RESERVE_BYTES: Long = 50L * 1024L * 1024L
    const val MAX_RESERVE_BYTES: Long = 1024L * 1024L * 1024L

    /** Bounds applied to user-configured per-file size limits. */
    const val MIN_SEGMENT_BYTES: Long = 10L * 1024L * 1024L
    const val MAX_SEGMENT_BYTES: Long = 64L * 1024L * 1024L * 1024L

    /** Free-space level below which video is considered unsafe. */
    fun criticalBytes(reserveBytes: Long): Long =
        reserveBytes.coerceAtLeast(0L) + VIDEO_SAFETY_HEADROOM_BYTES

    /**
     * Maps a measurement to a status. A negative [freeBytes] means the value
     * could not be trusted; combined with `available = false` this yields the
     * two non-recordable statuses.
     */
    fun statusFor(
        freeBytes: Long,
        warningBytes: Long,
        reserveBytes: Long,
        available: Boolean
    ): StorageStatus {
        if (!available) return StorageStatus.STORAGE_UNAVAILABLE
        if (freeBytes < 0L) return StorageStatus.STORAGE_ERROR
        if (freeBytes <= reserveBytes) return StorageStatus.RESERVE_REACHED
        if (freeBytes <= criticalBytes(reserveBytes)) return StorageStatus.CRITICAL
        if (freeBytes <= warningBytes) return StorageStatus.LOW
        return StorageStatus.NORMAL
    }

    /** True when every active recording must stop immediately. */
    fun shouldStopRecording(freeBytes: Long, reserveBytes: Long): Boolean =
        freeBytes < 0L || freeBytes <= reserveBytes

    /** True when a video recording can safely keep writing. */
    fun canContinueVideo(freeBytes: Long, reserveBytes: Long): Boolean =
        freeBytes > criticalBytes(reserveBytes)

    /** True when a new video recording is not blocked by the hard reserve. */
    fun canStartVideo(freeBytes: Long, reserveBytes: Long): Boolean =
        freeBytes > reserveBytes

    /** True when video is allowed but should be confirmed by the user first. */
    fun videoRequiresConfirmation(
        freeBytes: Long,
        warningBytes: Long,
        reserveBytes: Long
    ): Boolean =
        freeBytes > reserveBytes && freeBytes <= warningBytes

    /** True when an audio recording can start with the reserve + margin intact. */
    fun canStartAudio(
        freeBytes: Long,
        reserveBytes: Long,
        marginBytes: Long = DEFAULT_AUDIO_FALLBACK_MARGIN_BYTES
    ): Boolean =
        freeBytes > reserveBytes + marginBytes.coerceAtLeast(0L)

    /** Has the active file grown to its configured limit? */
    fun shouldRotateFile(fileLengthBytes: Long, limitBytes: Long): Boolean =
        limitBytes > 0L && fileLengthBytes >= limitBytes

    fun clampReserve(value: Long): Long = value.coerceIn(MIN_RESERVE_BYTES, MAX_RESERVE_BYTES)

    fun clampSegmentLimit(value: Long): Long =
        value.coerceIn(MIN_SEGMENT_BYTES, MAX_SEGMENT_BYTES)

    fun isValidReserve(value: Long): Boolean = value in MIN_RESERVE_BYTES..MAX_RESERVE_BYTES

    fun isValidSegmentLimit(value: Long): Boolean =
        value in MIN_SEGMENT_BYTES..MAX_SEGMENT_BYTES
}
