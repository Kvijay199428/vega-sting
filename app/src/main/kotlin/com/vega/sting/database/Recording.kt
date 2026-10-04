package com.vega.sting.database

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class RecordingType {
    AUDIO, VIDEO
}

@Entity(tableName = "recordings")
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
    val height: Int = 0
) {
    val hasKnownResolution: Boolean
        get() = width > 0 && height > 0

    /** Display form of the stored frame size, e.g. "1920x1080". */
    val resolutionLabel: String
        get() = "${width}x${height}"
}
