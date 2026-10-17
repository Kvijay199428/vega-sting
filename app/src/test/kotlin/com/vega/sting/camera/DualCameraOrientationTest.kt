package com.vega.sting.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dual-camera front feed needs its own rotation on top of [RotationResolver]:
 * the compositor renders it unmirrored, so the mirrored ("add") convention that
 * [RotationResolver] uses for a front camera is wrong, and the tested hardware's
 * front stream is a further 180° from its reported sensor orientation.
 *
 * Both facts are pinned here, together with the guarantee that the rear value is
 * untouched.
 */
class DualCameraOrientationTest {

    @Test
    fun `front feed is upright in portrait on the tested device`() {
        // Device-verified: the front sensor reports 270. The old mirrored
        // convention produced 270 and rendered upside down; the correct value is
        // 270 + 180 - 0 = 90.
        assertEquals(90, DualCameraOrientation.frontFeedRotationDegrees(270, 0))
    }

    @Test
    fun `front feed is upright in landscape on the tested device`() {
        // Device-verified after the convention fix: 270 + 180 - 90 = 0. (The
        // previous constant-only correction produced 180 here and was upside
        // down, because the mirrored convention's error grows with 2x rotation.)
        assertEquals(0, DualCameraOrientation.frontFeedRotationDegrees(270, 90))
    }

    @Test
    fun `front feed tracks the device rotation with the rear convention`() {
        // The device rotation is subtracted, not added.
        assertEquals(90, DualCameraOrientation.frontFeedRotationDegrees(270, 0))
        assertEquals(0, DualCameraOrientation.frontFeedRotationDegrees(270, 90))
        assertEquals(270, DualCameraOrientation.frontFeedRotationDegrees(270, 180))
        assertEquals(180, DualCameraOrientation.frontFeedRotationDegrees(270, 270))
    }

    @Test
    fun `front feed equals the rear-style rotation for a 180-degree mount offset`() {
        // Structural definition: rear convention applied to (sensor + 180).
        for (sensor in listOf(0, 90, 180, 270)) {
            for (device in listOf(0, 90, 180, 270)) {
                val expected = RotationResolver.resolveDegrees(
                    sensorOrientation = (sensor + DualCameraOrientation.FRONT_FEED_EXTRA_DEGREES) % 360,
                    isFrontFacing = false,
                    deviceRotationDegrees = device
                )
                assertEquals(expected, DualCameraOrientation.frontFeedRotationDegrees(sensor, device))
            }
        }
    }

    @Test
    fun `the rear feed keeps the shared resolver value`() {
        // Regression guard for the on-device-verified rear feed: a 90° rear
        // sensor in portrait stays 90, in landscape 0. The front helper must not
        // have changed this.
        assertEquals(90, RotationResolver.resolveDegrees(90, isFrontFacing = false, deviceRotationDegrees = 0))
        assertEquals(0, RotationResolver.resolveDegrees(90, isFrontFacing = false, deviceRotationDegrees = 90))
        // The uncorrected mirrored front value is what the fix replaces.
        assertEquals(270, RotationResolver.resolveDegrees(270, isFrontFacing = true, deviceRotationDegrees = 0))
    }

    @Test
    fun `correction is normalised to 0 until 360`() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (device in listOf(0, 90, 180, 270, 360, 450)) {
                val d = DualCameraOrientation.frontFeedRotationDegrees(sensor, device)
                assertTrue("sensor=$sensor device=$device degrees=$d", d in 0 until 360)
            }
        }
    }

    @Test
    fun `correction constant is 180`() {
        assertEquals(180, DualCameraOrientation.FRONT_FEED_EXTRA_DEGREES)
    }
}
