package com.vega.sting.orientation

import android.view.Surface
import kotlin.math.abs

/**
 * Pure orientation state machine behind [OrientationManager].
 *
 * Deliberately free of Android services, `Looper` and I/O so the angle → rotation
 * mapping, the hysteresis gate and the first-reading transition are unit-testable
 * on the JVM. Only the compile-time `Surface.ROTATION_*` constants are referenced.
 *
 * ## Rotation convention (important)
 * [android.view.OrientationEventListener] reports the angle the device has been
 * rotated **clockwise** from its natural orientation, whereas the `Surface`
 * rotation constants describe the **counter-clockwise** display rotation (see
 * `Display#getRotation()`). The two conventions are opposite, so a 90° sensor
 * reading is `ROTATION_270` and a 270° reading is `ROTATION_90`. Feeding the
 * sensor angle straight in as a display rotation inverts landscape and turns
 * every landscape recording 180°.
 */
internal class OrientationState(
    private val stableSamples: Int = STABLE_SAMPLES,
    private val deadZoneDegrees: Double = DEAD_ZONE_DEGREES
) {

    /** Always a `Surface.ROTATION_*` constant (0..3), never degrees. */
    @Volatile
    var rotation: Int = Surface.ROTATION_0
        private set

    /** True once a valid reading has been observed this session. */
    @Volatile
    var hasReading: Boolean = false
        private set

    // Debounce state; only touched on the listener thread.
    private var pendingRotation = -1
    private var pendingCount = 0

    /** Starts a fresh session: seeds the rotation and clears the reading. */
    fun reset(seedRotation: Int) {
        rotation = seedRotation
        hasReading = false
        pendingRotation = -1
        pendingCount = 0
    }

    /**
     * Feeds one clockwise [rawAngle] (0–359). Returns `true` exactly on the first
     * *valid* reading of the session, so the caller can release its latch once.
     */
    fun onAngle(rawAngle: Int): Boolean {
        val angle = rawAngle.toDouble()
        val candidate = rotationForAngle(angle)

        // Dead-zone: only accept a reading that sits clearly inside the
        // candidate's sector, away from the ±45° boundaries, so the reported
        // rotation does not jitter while the device is near a boundary.
        if (!isClearOfBoundary(angle, candidate)) return false

        val first = !hasReading
        hasReading = true

        if (candidate == rotation) {
            pendingRotation = -1
            pendingCount = 0
            return first
        }

        // Debounce: require [stableSamples] consecutive readings of the new
        // sector before committing the flip.
        if (pendingRotation == candidate) {
            pendingCount++
            if (pendingCount >= stableSamples) {
                rotation = candidate
                pendingRotation = -1
                pendingCount = 0
            }
        } else {
            pendingRotation = candidate
            pendingCount = 1
        }
        return first
    }

    /**
     * Maps a clockwise sensor [angle] to the equivalent counter-clockwise
     * `Surface.ROTATION_*` constant. 90° and 270° are crossed on purpose — see
     * the class docs.
     */
    fun rotationForAngle(angle: Double): Int = when {
        angle >= 315.0 || angle < 45.0 -> Surface.ROTATION_0
        angle < 135.0 -> Surface.ROTATION_270
        angle < 225.0 -> Surface.ROTATION_180
        else -> Surface.ROTATION_90
    }

    /**
     * True when [angle] sits at least [deadZoneDegrees] inside the sector that
     * maps to [rotation], i.e. well away from the ±45° boundaries.
     */
    fun isClearOfBoundary(angle: Double, rotation: Int): Boolean {
        val center = angleCenterDegrees(rotation)
        var delta = abs(angle - center)
        if (delta > 180.0) delta = 360.0 - delta
        return delta <= SECTOR_HALF_DEGREES - deadZoneDegrees
    }

    /**
     * The sector centre, expressed in clockwise sensor-angle space, for a
     * `Surface.ROTATION_*` [rotation]. Mirrors [rotationForAngle], so the
     * 90°/270° crossing is applied consistently.
     */
    private fun angleCenterDegrees(rotation: Int): Double = when (rotation) {
        Surface.ROTATION_90 -> 270.0
        Surface.ROTATION_180 -> 180.0
        Surface.ROTATION_270 -> 90.0
        else -> 0.0
    }

    companion object {
        internal const val STABLE_SAMPLES = 3
        internal const val DEAD_ZONE_DEGREES = 20.0
        internal const val SECTOR_HALF_DEGREES = 45.0
    }
}
