package com.vega.sting.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RecordingType {
    AUDIO, VIDEO
}

/** Lifecycle of an individual segment row. */
enum class SegmentStatus {
    /** A part that is currently being written and has not been finalized. */
    RECORDING,

    /** A part whose container was finalized and confirmed on disk. */
    FINALIZED,

    /** A part that never completed cleanly (crash, disk-full, encoder error). */
    FAILED
}

/** Why a segment stopped being written. */
enum class FinalizationReason {
    /** The configured per-file size limit was reached. */
    SIZE_LIMIT,

    /** The user pressed STOP. */
    MANUAL_STOP,

    /** The storage reserve was reached. */
    STORAGE_LIMIT,

    /** The encoder or camera pipeline failed. */
    ERROR,

    /** The recorder switched recording type (video -> audio). */
    SWITCH
}

@Entity(
    tableName = "recordings",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["sessionId", "partIndex"], unique = true)
    ]
)
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val timestamp: Long,
    val path: String,
    val originalPath: String? = null,
    val type: RecordingType,
    val codec: String,
    val size: Long,
    val deleted: Boolean = false,
    // When this recording was moved to trash. Null while active. Retention is
    // measured from this value, not from `timestamp`, because `timestamp` is the
    // moment of capture and can be arbitrarily old by the time it is trashed.
    val deletedAt: Long? = null,
    // Encoder frame size actually applied, as opposed to the requested preset.
    // 0 means unknown (pre-v3 rows, or audio recordings which have no video).
    val width: Int = 0,
    val height: Int = 0,
    // Groups every part produced by one continuous recording. Null only for
    // legacy rows written before session metadata existed.
    val sessionId: String? = null,
    // Sequential part number within the session, starting at 1.
    val partIndex: Int = 1,
    val segmentStatus: String = SegmentStatus.FINALIZED.name,
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val finalizationReason: String? = null,
    // True once the overall session (all parts) has finished.
    val isSessionComplete: Boolean = false
) {
    val hasKnownResolution: Boolean
        get() = width > 0 && height > 0

    /** Display form of the stored frame size, e.g. "1920x1080". */
    val resolutionLabel: String
        get() = "${width}x${height}"

    val segmentStatusEnum: SegmentStatus
        get() = runCatching { SegmentStatus.valueOf(segmentStatus) }
            .getOrDefault(SegmentStatus.FINALIZED)
}
