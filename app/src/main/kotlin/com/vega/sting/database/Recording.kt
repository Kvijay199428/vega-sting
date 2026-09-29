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
    val deleted: Boolean = false
)
