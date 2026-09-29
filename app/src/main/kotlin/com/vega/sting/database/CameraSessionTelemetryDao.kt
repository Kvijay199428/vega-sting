package com.vega.sting.database

import androidx.room.*

/**
 * DAO for [CameraSessionTelemetry].
 * Provides queries needed by the fallback engine and preset selector.
 */
@Dao
interface CameraSessionTelemetryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: CameraSessionTelemetry)

    /**
     * Returns all failed session configs as "WxH@FPS" strings.
     * Used to build the blacklist for [SafePresetSelector].
     */
    @Query("""
        SELECT DISTINCT (resolution || '@' || fps) 
        FROM camera_session_telemetry 
        WHERE success = 0
    """)
    suspend fun getFailedConfigs(): List<String>

    /**
     * Success rate for a specific config combo.
     * Returns null if no sessions recorded for that combo.
     */
    @Query("""
        SELECT CAST(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS REAL) / COUNT(*) 
        FROM camera_session_telemetry 
        WHERE resolution = :resolution AND fps = :fps AND codec = :codec
    """)
    suspend fun getSuccessRate(resolution: String, fps: Int, codec: String): Double?

    /**
     * Total number of failed sessions for a given config.
     */
    @Query("""
        SELECT COUNT(*) FROM camera_session_telemetry 
        WHERE resolution = :resolution AND fps = :fps AND success = 0
    """)
    suspend fun getFailureCount(resolution: String, fps: Int): Int

    /**
     * Most recent N sessions for diagnostics.
     */
    @Query("SELECT * FROM camera_session_telemetry ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSessions(limit: Int = 50): List<CameraSessionTelemetry>

    /**
     * Cleanup: delete sessions older than the given timestamp.
     */
    @Query("DELETE FROM camera_session_telemetry WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
