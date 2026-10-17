package com.vega.sting.orientation

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OrientationState] is the pure heart of [OrientationManager]: the first-reading
 * transition (which releases the recording latch), the angle → rotation mapping
 * and the hysteresis gate all live here. A regression in the mapping turns every
 * landscape recording 180°, so the convention is pinned explicitly.
 */
class OrientationStateTest {

    @Test
    fun `first valid reading marks hasReading and reports it exactly once`() {
        val state = OrientationState()
        state.reset(Surface.ROTATION_0)

        assertFalse(state.hasReading)
        assertTrue("the first reading must release the latch", state.onAngle(0))
        assertTrue(state.hasReading)
        assertFalse("later readings must not re-signal", state.onAngle(10))
        assertFalse(state.onAngle(20))
    }

    @Test
    fun `reset clears the reading so a new session waits again`() {
        val state = OrientationState()
        state.reset(Surface.ROTATION_0)
        assertTrue(state.onAngle(0))

        state.reset(Surface.ROTATION_0)
        assertFalse(state.hasReading)
        assertTrue(state.onAngle(0))
    }

    @Test
    fun `rotationForAngle crosses 90 and 270 between the two conventions`() {
        val state = OrientationState()
        // OrientationEventListener reports a *clockwise* angle while Surface
        // ROTATION_* is the *counter-clockwise* display convention.
        assertEquals(Surface.ROTATION_0, state.rotationForAngle(0.0))
        assertEquals(Surface.ROTATION_270, state.rotationForAngle(90.0))
        assertEquals(Surface.ROTATION_180, state.rotationForAngle(180.0))
        assertEquals(Surface.ROTATION_90, state.rotationForAngle(270.0))
    }

    @Test
    fun `rotationForAngle uses 45 degree sector boundaries`() {
        val state = OrientationState()
        assertEquals(Surface.ROTATION_0, state.rotationForAngle(44.9))
        assertEquals(Surface.ROTATION_270, state.rotationForAngle(45.0))
        assertEquals(Surface.ROTATION_270, state.rotationForAngle(134.9))
        assertEquals(Surface.ROTATION_180, state.rotationForAngle(135.0))
        assertEquals(Surface.ROTATION_180, state.rotationForAngle(224.9))
        assertEquals(Surface.ROTATION_90, state.rotationForAngle(225.0))
        assertEquals(Surface.ROTATION_90, state.rotationForAngle(314.9))
        assertEquals(Surface.ROTATION_0, state.rotationForAngle(315.0))
        assertEquals(Surface.ROTATION_0, state.rotationForAngle(359.0))
    }

    @Test
    fun `readings near a sector centre are accepted and boundary readings rejected`() {
        val state = OrientationState()
        assertTrue(state.isClearOfBoundary(0.0, Surface.ROTATION_0))
        assertTrue(state.isClearOfBoundary(20.0, Surface.ROTATION_0))
        assertFalse(state.isClearOfBoundary(30.0, Surface.ROTATION_0))
        assertFalse(state.isClearOfBoundary(45.0, Surface.ROTATION_0))

        // ROTATION_90's sector centre is 270° in clockwise angle space.
        assertTrue(state.isClearOfBoundary(270.0, Surface.ROTATION_90))
        assertFalse(state.isClearOfBoundary(225.0, Surface.ROTATION_90))

        assertTrue(state.isClearOfBoundary(180.0, Surface.ROTATION_180))
        assertTrue(state.isClearOfBoundary(90.0, Surface.ROTATION_270))
        assertFalse(state.isClearOfBoundary(45.0, Surface.ROTATION_270))
    }

    @Test
    fun `a steady hold at the seeded rotation still registers a reading`() {
        val state = OrientationState()
        state.reset(Surface.ROTATION_0)

        assertTrue(state.onAngle(5))
        assertTrue(state.hasReading)
        assertEquals(Surface.ROTATION_0, state.rotation)
    }

    @Test
    fun `a rotation change commits only after the stable sample count`() {
        val state = OrientationState()
        state.reset(Surface.ROTATION_0)

        assertTrue(state.onAngle(270)) // first reading; candidate ROTATION_90
        assertEquals(Surface.ROTATION_0, state.rotation)
        state.onAngle(270)
        assertEquals(Surface.ROTATION_0, state.rotation)
        state.onAngle(270)
        assertEquals(Surface.ROTATION_90, state.rotation)
    }

    @Test
    fun `jitter near a boundary never produces a reading`() {
        val state = OrientationState()
        state.reset(Surface.ROTATION_0)

        assertFalse(state.onAngle(45))
        assertFalse(state.hasReading)
        assertFalse(state.onAngle(40))
        assertFalse(state.hasReading)
        assertEquals(Surface.ROTATION_0, state.rotation)
    }

    @Test
    fun `both landscape directions resolve to distinct rotations`() {
        // Guards the 90/270 crossing: the two landscape holds must not collapse
        // onto the same Surface rotation.
        val clockwise = OrientationState()
        clockwise.reset(Surface.ROTATION_0)
        clockwise.onAngle(90)
        repeat(3) { clockwise.onAngle(90) }
        assertEquals(Surface.ROTATION_270, clockwise.rotation)

        val counterClockwise = OrientationState()
        counterClockwise.reset(Surface.ROTATION_0)
        counterClockwise.onAngle(270)
        repeat(3) { counterClockwise.onAngle(270) }
        assertEquals(Surface.ROTATION_90, counterClockwise.rotation)
    }

    @Test
    fun `stable sample count is configurable`() {
        val state = OrientationState(stableSamples = 2)
        state.reset(Surface.ROTATION_0)

        state.onAngle(180)
        assertEquals(Surface.ROTATION_0, state.rotation)
        state.onAngle(180)
        assertEquals(Surface.ROTATION_180, state.rotation)
    }

    @Test
    fun `a later flip updates the rotation`() {
        val state = OrientationState()
        state.reset(Surface.ROTATION_0)
        state.onAngle(0)

        repeat(3) { state.onAngle(180) }
        assertEquals(Surface.ROTATION_180, state.rotation)
    }
}
