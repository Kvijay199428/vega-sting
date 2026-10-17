package com.vega.sting.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The master watermark switch must gate both overlay items identically in every
 * pipeline, and the per-item switches must refine rather than override it.
 */
class OverlaySettingsComposerTest {

    private fun compose(
        watermarkEnabled: Boolean = true,
        timestampEnabled: Boolean = true,
        timestampPosition: String = "TOP_CENTER",
        timestampFormat: String = "yyyy-MM-dd HH:mm:ss",
        signature: String = "VEGA STING",
        signaturePosition: String = "BOTTOM_LEFT",
        textSizeSp: Int = 18
    ) = OverlaySettingsComposer.compose(
        watermarkEnabled = watermarkEnabled,
        timestampEnabled = timestampEnabled,
        timestampPosition = timestampPosition,
        timestampFormat = timestampFormat,
        signature = signature,
        signaturePosition = signaturePosition,
        textSizeSp = textSizeSp
    )

    @Test
    fun `master switch on keeps enabled items`() {
        val settings = compose()
        assertEquals(true, settings.timestampEnabled)
        assertEquals("VEGA STING", settings.signature)
    }

    @Test
    fun `master switch off suppresses both items`() {
        val settings = compose(watermarkEnabled = false)
        assertEquals(false, settings.timestampEnabled)
        assertEquals("", settings.signature)
    }

    @Test
    fun `timestamp switch off only disables the timestamp`() {
        val settings = compose(timestampEnabled = false)
        assertEquals(false, settings.timestampEnabled)
        assertEquals("VEGA STING", settings.signature)
    }

    @Test
    fun `positions are parsed and unknown values fall back`() {
        val settings = compose(
            timestampPosition = "BOTTOM_RIGHT",
            signaturePosition = "NOT_A_POSITION"
        )
        assertEquals(OverlayPosition.BOTTOM_RIGHT, settings.timestampPosition)
        assertEquals(OverlayPosition.TOP_CENTER, settings.signaturePosition)
    }

    @Test
    fun `format and text size pass through unchanged`() {
        val settings = compose(timestampFormat = "HH:mm", textSizeSp = 24)
        assertEquals("HH:mm", settings.timestampFormat)
        assertEquals(24, settings.textSizeSp)
    }
}
