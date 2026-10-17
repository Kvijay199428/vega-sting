package com.vega.sting.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay must be authored against the frame the player actually displays.
 * The single-camera buffer is sensor-native and carries a rotation hint, while
 * the dual-camera composite is already upright (hint 0). These tests pin the
 * mapping both pipelines depend on.
 */
class OverlayGeometryTest {

    private val eps = 1e-4f

    @Test
    fun `base scale references the 720px short edge`() {
        assertEquals(1f, OverlayGeometry.baseScale(720, 1280), eps)
        assertEquals(1f, OverlayGeometry.baseScale(1280, 720), eps)
        assertEquals(1.5f, OverlayGeometry.baseScale(1080, 1920), eps)
    }

    @Test
    fun `base scale never drops below one`() {
        assertEquals(1f, OverlayGeometry.baseScale(480, 640), eps)
        assertEquals(1f, OverlayGeometry.baseScale(1, 1), eps)
    }

    @Test
    fun `quarter turns are the portrait hints`() {
        assertTrue(OverlayGeometry.isPortraitHint(90))
        assertTrue(OverlayGeometry.isPortraitHint(270))
        assertFalse(OverlayGeometry.isPortraitHint(0))
        assertFalse(OverlayGeometry.isPortraitHint(180))
    }

    @Test
    fun `single camera landscape buffer with a quarter hint is authored portrait`() {
        // 1920x1080 buffer + hint 90 => the displayed frame is 1080x1920.
        assertEquals(1080, OverlayGeometry.logicalWidth(1920, 1080, 90))
        assertEquals(1920, OverlayGeometry.logicalHeight(1920, 1080, 90))
    }

    @Test
    fun `dual portrait composite with no hint stays portrait`() {
        assertEquals(720, OverlayGeometry.logicalWidth(720, 1280, 0))
        assertEquals(1280, OverlayGeometry.logicalHeight(720, 1280, 0))
    }

    @Test
    fun `dual landscape composite with no hint stays landscape`() {
        assertEquals(1280, OverlayGeometry.logicalWidth(1280, 720, 0))
        assertEquals(720, OverlayGeometry.logicalHeight(1280, 720, 0))
    }

    @Test
    fun `half turn hint keeps the buffer orientation`() {
        assertEquals(1920, OverlayGeometry.logicalWidth(1920, 1080, 180))
        assertEquals(1080, OverlayGeometry.logicalHeight(1920, 1080, 180))
    }
}
