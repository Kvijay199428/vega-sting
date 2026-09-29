package com.vega.sting.camera

import android.util.Log
import com.vega.sting.database.CameraSessionTelemetryDao

/**
 * Self-learning fallback engine.
 *
 * Queries session telemetry to identify unstable recording configurations
 * and automatically selects the next-safest preset.
 *
 * Flow:
 *   1. Load blacklisted configs from telemetry (≥2 failures)
 *   2. Pass blacklist to [SafePresetSelector]
 *   3. Selector returns ranked presets excluding blacklisted combos
 *   4. Engine picks the top-ranked safe preset
 *
 * Example:
 *   4K@60 fails twice on Xiaomi → blacklisted
 *   → auto-fallback to 1080p@30 (score 95 vs score 40)
 */
class DynamicFallbackEngine(
    private val telemetryDao: CameraSessionTelemetryDao
) {

    companion object {
        private const val TAG = "FallbackEngine"

        /** Number of failures before a config is blacklisted. */
        private const val FAILURE_THRESHOLD = 2
    }

    /**
     * Returns the best safe preset, excluding configurations that have
     * failed [FAILURE_THRESHOLD] or more times in telemetry history.
     */
    suspend fun selectSafePreset(
        profile: DeviceProfile,
        cameraId: String = "0",
        preference: VideoPreference = VideoPreference()
    ): RecordingPreset {
        val blacklisted = getBlacklistedConfigs()

        if (blacklisted.isNotEmpty()) {
            Log.w(TAG, "Blacklisted configs from telemetry: $blacklisted")
        }

        val preset = SafePresetSelector.selectBest(
            profile = profile,
            cameraId = cameraId,
            blacklistedConfigs = blacklisted,
            preference = preference
        )

        Log.i(TAG, "Selected preset: ${preset.width}x${preset.height}@${preset.fps} " +
                "bitrate=${preset.bitrate} codec=${preset.codec} score=${preset.score}")

        return preset
    }

    /**
     * Returns all ranked presets excluding blacklisted configs.
     */
    suspend fun selectSafePresets(
        profile: DeviceProfile,
        cameraId: String = "0",
        preference: VideoPreference = VideoPreference()
    ): List<RecordingPreset> {
        val blacklisted = getBlacklistedConfigs()
        return SafePresetSelector.selectPresets(profile, cameraId, blacklisted, preference)
    }

    // ── Internal ────────────────────────────────────────────────────────

    private suspend fun getBlacklistedConfigs(): Set<String> {
        return try {
            val failedConfigs = telemetryDao.getFailedConfigs()

            // Only blacklist if failure count reaches threshold
            failedConfigs.filter { config ->
                val parts = config.split("@")
                if (parts.size == 2) {
                    val resolution = parts[0]
                    val fps = parts[1].toIntOrNull() ?: return@filter false
                    telemetryDao.getFailureCount(resolution, fps) >= FAILURE_THRESHOLD
                } else {
                    false
                }
            }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query telemetry, proceeding without blacklist", e)
            emptySet()
        }
    }
}
