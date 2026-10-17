package com.vega.sting.overlay

import android.content.Context
import com.vega.sting.settings.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Reads the persisted overlay settings through the single shared
 * [OverlaySettingsComposer], so the single-camera recorder and the dual-camera
 * recorder/preview always render the same watermark.
 *
 * Blocking by design: every caller already runs on a background thread when it
 * builds its pipeline, matching the original single-camera behaviour.
 */
object OverlaySettingsLoader {

    fun load(context: Context): OverlaySettings {
        return try {
            val settingsManager = SettingsManager(context)
            runBlocking {
                OverlaySettingsComposer.compose(
                    watermarkEnabled = settingsManager.watermarkEnabled.first(),
                    timestampEnabled = settingsManager.timestampEnabled.first(),
                    timestampPosition = settingsManager.timestampPosition.first(),
                    timestampFormat = settingsManager.timestampFormat.first(),
                    signature = settingsManager.signature.first(),
                    signaturePosition = settingsManager.signaturePosition.first(),
                    textSizeSp = settingsManager.overlayTextSize.first()
                )
            }
        } catch (_: Exception) {
            OverlaySettings()
        }
    }
}
