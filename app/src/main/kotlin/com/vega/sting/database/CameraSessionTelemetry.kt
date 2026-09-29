package com.vega.sting.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks every camera recording session outcome.
 * Used by [DynamicFallbackEngine] to blacklist unstable configurations
 * and by [SafePresetSelector] to avoid known-bad combos.
 */
@Entity(tableName = "camera_session_telemetry")
data class CameraSessionTelemetry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Session start timestamp (epoch millis). */
    val timestamp: Long = 0L,

    /** Build.MODEL */
    val device: String = "",

    /** Camera2 cameraId used. */
    val cameraId: String = "",

    /** "WxH" e.g. "1920x1080" */
    val resolution: String = "",

    /** Frames per second. */
    val fps: Int = 0,

    /** Video encoding bitrate in bps. */
    val bitrate: Int = 0,

    /** Codec name: "H264", "HEVC", etc. */
    val codec: String = "",

    /** Whether video stabilization was enabled. */
    val stabilization: Boolean = false,

    /** CameraX extension mode applied, if any. */
    val extensionMode: String? = null,

    /** true if the recording completed without errors. */
    val success: Boolean = true,

    /** Error message if recording failed. */
    val failureReason: String? = null,

    /** Duration of the recording in milliseconds, 0 if failed at start. */
    val durationMs: Long = 0L
)
