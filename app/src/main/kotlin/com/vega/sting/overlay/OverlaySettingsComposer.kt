package com.vega.sting.overlay

/**
 * Pure assembly of [OverlaySettings] from raw persisted values.
 *
 * The single-camera recorder, the dual-camera recorder and the dual-camera
 * preview all funnel through here, so one watermark switch governs every
 * pipeline identically. Kept free of Android types so it is unit-testable.
 */
object OverlaySettingsComposer {

    fun compose(
        watermarkEnabled: Boolean,
        timestampEnabled: Boolean,
        timestampPosition: String,
        timestampFormat: String,
        signature: String,
        signaturePosition: String,
        textSizeSp: Int
    ): OverlaySettings = OverlaySettings(
        // The master watermark switch gates both the timestamp and the
        // signature; the per-item switches refine it rather than override it.
        timestampEnabled = watermarkEnabled && timestampEnabled,
        timestampPosition = OverlayPosition.from(timestampPosition),
        timestampFormat = timestampFormat,
        signature = if (watermarkEnabled) signature else "",
        signaturePosition = OverlayPosition.from(signaturePosition),
        textSizeSp = textSizeSp
    )
}
