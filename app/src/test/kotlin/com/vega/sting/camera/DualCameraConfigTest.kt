package com.vega.sting.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted dual-camera preferences are untrusted input (an older build, a
 * restored backup or a corrupted DataStore). These tests pin the documented
 * defaults and prove that [DualCameraConfig.fromStored] always yields a usable
 * configuration.
 */
class DualCameraConfigTest {

    @Test
    fun `defaults match the documented values`() {
        val d = DualCameraConfig.DEFAULTS
        assertFalse(d.enabled)
        assertEquals(DualCameraLayout.AUTO, d.layout)
        assertTrue(d.frontOnTop)
        assertTrue(d.frontOnLeft)
        assertEquals(DualCameraSplitRatio.RATIO_50_50, d.splitRatio)
        assertEquals(DualCameraQuality.Q_720P, d.quality)
        assertEquals(30, d.fps)
        assertFalse(d.mirrorFront)
        assertEquals(DualCameraScalingMode.CROP_TO_FILL, d.scalingMode)
    }

    @Test
    fun `fromStored with all nulls falls back to defaults`() {
        val c = DualCameraConfig.fromStored(
            enabled = null, layout = null, frontOnTop = null, frontOnLeft = null,
            splitRatio = null, quality = null, fps = null, mirrorFront = null, scalingMode = null
        )
        assertEquals(DualCameraConfig.DEFAULTS, c)
    }

    @Test
    fun `fromStored ignores garbage enum names`() {
        val c = DualCameraConfig.fromStored(
            enabled = true, layout = "NOT A LAYOUT", frontOnTop = false, frontOnLeft = true,
            splitRatio = "bogus", quality = "??", fps = 30, mirrorFront = true, scalingMode = "nope"
        )
        assertTrue(c.enabled)
        assertEquals(DualCameraLayout.AUTO, c.layout)
        assertFalse(c.frontOnTop)
        assertEquals(DualCameraSplitRatio.RATIO_50_50, c.splitRatio)
        assertEquals(DualCameraQuality.Q_720P, c.quality)
        assertTrue(c.mirrorFront)
        assertEquals(DualCameraScalingMode.CROP_TO_FILL, c.scalingMode)
    }

    @Test
    fun `fromStored round-trips every enum value`() {
        for (layout in DualCameraLayout.entries) {
            for (ratio in DualCameraSplitRatio.entries) {
                for (quality in DualCameraQuality.entries) {
                    for (scaling in DualCameraScalingMode.entries) {
                        val c = DualCameraConfig.fromStored(
                            enabled = true, layout = layout.name, frontOnTop = true,
                            frontOnLeft = false, splitRatio = ratio.name, quality = quality.name,
                            fps = 24, mirrorFront = true, scalingMode = scaling.name
                        )
                        assertEquals(layout, c.layout)
                        assertEquals(ratio, c.splitRatio)
                        assertEquals(quality, c.quality)
                        assertEquals(scaling, c.scalingMode)
                    }
                }
            }
        }
    }

    @Test
    fun `sanitized clamps fps to a supported rate`() {
        assertEquals(60, DualCameraConfig(fps = 999).sanitized().fps)
        assertEquals(60, DualCameraConfig(fps = 60).sanitized().fps)
        assertEquals(24, DualCameraConfig(fps = 25).sanitized().fps)
        assertEquals(30, DualCameraConfig(fps = 0).sanitized().fps)
        assertEquals(24, DualCameraConfig(fps = 24).sanitized().fps)
    }

    @Test
    fun `nextLayout cycles through every value and back`() {
        var layout = DualCameraLayout.AUTO
        val seen = mutableListOf(layout)
        repeat(DualCameraLayout.entries.size - 1) {
            layout = DualCameraConfig.nextLayout(layout)
            seen += layout
        }
        assertEquals(DualCameraLayout.entries.toList(), seen)
        assertEquals(DualCameraLayout.AUTO, DualCameraConfig.nextLayout(layout))
    }

    @Test
    fun `split ratio fraction maps back to the nearest option`() {
        assertEquals(DualCameraSplitRatio.RATIO_40_60, DualCameraSplitRatio.fromFraction(0.4f))
        assertEquals(DualCameraSplitRatio.RATIO_50_50, DualCameraSplitRatio.fromFraction(0.5f))
        assertEquals(DualCameraSplitRatio.RATIO_60_40, DualCameraSplitRatio.fromFraction(0.6f))
        assertEquals(DualCameraSplitRatio.RATIO_50_50, DualCameraSplitRatio.fromFraction(0.51f))
    }

    @Test
    fun `quality dimensions follow the requested orientation`() {
        val q = DualCameraQuality.Q_1080P
        assertEquals(1080, q.width(portrait = true))
        assertEquals(1920, q.height(portrait = true))
        assertEquals(1920, q.width(portrait = false))
        assertEquals(1080, q.height(portrait = false))
    }
}
