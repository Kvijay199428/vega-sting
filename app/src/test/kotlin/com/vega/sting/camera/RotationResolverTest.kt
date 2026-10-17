package com.vega.sting.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RotationResolver] is the shared authority for both the single-camera and
 * dual-camera pipelines; a regression here would silently rotate every recorded
 * video, so the rear/front signs, the device postures and the offset are all
 * pinned.
 */
class RotationResolverTest {

    @Test
    fun `rear camera subtracts the device rotation`() {
        // A rear sensor mounted at 90° needs a 90° clockwise correction in portrait.
        assertEquals(90, RotationResolver.resolveDegrees(90, isFrontFacing = false, deviceRotationDegrees = 0))
        // Turning the phone 90° clockwise cancels the sensor's 90°.
        assertEquals(0, RotationResolver.resolveDegrees(90, isFrontFacing = false, deviceRotationDegrees = 90))
        assertEquals(270, RotationResolver.resolveDegrees(90, isFrontFacing = false, deviceRotationDegrees = 180))
        assertEquals(180, RotationResolver.resolveDegrees(90, isFrontFacing = false, deviceRotationDegrees = 270))
    }

    @Test
    fun `rear camera with a 270 sensor is normalised`() {
        assertEquals(270, RotationResolver.resolveDegrees(270, isFrontFacing = false, deviceRotationDegrees = 0))
        assertEquals(90, RotationResolver.resolveDegrees(270, isFrontFacing = false, deviceRotationDegrees = 180))
    }

    @Test
    fun `front camera adds the device rotation`() {
        assertEquals(270, RotationResolver.resolveDegrees(270, isFrontFacing = true, deviceRotationDegrees = 0))
        assertEquals(0, RotationResolver.resolveDegrees(270, isFrontFacing = true, deviceRotationDegrees = 90))
        assertEquals(90, RotationResolver.resolveDegrees(270, isFrontFacing = true, deviceRotationDegrees = 180))
        assertEquals(180, RotationResolver.resolveDegrees(270, isFrontFacing = true, deviceRotationDegrees = 270))
    }

    @Test
    fun `front camera wraps past 360`() {
        assertEquals(0, RotationResolver.resolveDegrees(90, isFrontFacing = true, deviceRotationDegrees = 270))
    }

    @Test
    fun `result is always normalised to 0 until 360`() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (device in listOf(0, 90, 180, 270)) {
                for (front in listOf(true, false)) {
                    val d = RotationResolver.resolveDegrees(sensor, front, device)
                    assertTrue("degrees=$d", d in 0 until 360)
                }
            }
        }
    }

    @Test
    fun `offset is applied exactly once`() {
        assertEquals(
            180,
            RotationResolver.resolveDegrees(90, isFrontFacing = false, deviceRotationDegrees = 0, offsetDegrees = 90)
        )
        // An offset that would overflow wraps cleanly.
        assertEquals(
            0,
            RotationResolver.resolveDegrees(270, isFrontFacing = false, deviceRotationDegrees = 0, offsetDegrees = 90)
        )
    }

    @Test
    fun `surface rotation constants map to degrees`() {
        assertEquals(0, RotationResolver.surfaceRotationToDegrees(android.view.Surface.ROTATION_0))
        assertEquals(90, RotationResolver.surfaceRotationToDegrees(android.view.Surface.ROTATION_90))
        assertEquals(180, RotationResolver.surfaceRotationToDegrees(android.view.Surface.ROTATION_180))
        assertEquals(270, RotationResolver.surfaceRotationToDegrees(android.view.Surface.ROTATION_270))
    }

    @Test
    fun `portrait postures are 0 and 180 only`() {
        assertTrue(RotationResolver.isPortraitPosture(0))
        assertTrue(RotationResolver.isPortraitPosture(180))
        assertFalse(RotationResolver.isPortraitPosture(90))
        assertFalse(RotationResolver.isPortraitPosture(270))
    }
}
