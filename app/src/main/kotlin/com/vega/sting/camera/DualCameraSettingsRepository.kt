package com.vega.sting.camera

import com.vega.sting.settings.SettingsManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The one write path for the dual-camera configuration. Everything reads via
 * [config] and writes via the setters here, so the Settings UI, the preview and
 * the recording pipeline can never drift apart.
 */
class DualCameraSettingsRepository(private val settingsManager: SettingsManager) {

    val config: Flow<DualCameraConfig> = settingsManager.dualCameraConfig

    suspend fun setEnabled(enabled: Boolean) =
        settingsManager.updateDualCameraConfig(current().copy(enabled = enabled))

    suspend fun setLayout(layout: DualCameraLayout) =
        settingsManager.updateDualCameraConfig(current().copy(layout = layout))

    suspend fun setFrontOnTop(frontOnTop: Boolean) =
        settingsManager.updateDualCameraConfig(current().copy(frontOnTop = frontOnTop))

    suspend fun setFrontOnLeft(frontOnLeft: Boolean) =
        settingsManager.updateDualCameraConfig(current().copy(frontOnLeft = frontOnLeft))

    suspend fun setSplitRatio(splitRatio: DualCameraSplitRatio) =
        settingsManager.updateDualCameraConfig(current().copy(splitRatio = splitRatio))

    suspend fun setQuality(quality: DualCameraQuality) =
        settingsManager.updateDualCameraConfig(current().copy(quality = quality))

    suspend fun setFps(fps: Int) =
        settingsManager.updateDualCameraConfig(current().copy(fps = fps))

    suspend fun setMirrorFront(mirrorFront: Boolean) =
        settingsManager.updateDualCameraConfig(current().copy(mirrorFront = mirrorFront))

    suspend fun setScalingMode(mode: DualCameraScalingMode) =
        settingsManager.updateDualCameraConfig(current().copy(scalingMode = mode))

    /** Restores and persists every documented default. */
    suspend fun reset() = settingsManager.resetDualCameraConfig()

    private suspend fun current(): DualCameraConfig =
        settingsManager.dualCameraConfig.first()
}
