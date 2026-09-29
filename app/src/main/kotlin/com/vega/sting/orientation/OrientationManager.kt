package com.vega.sting.orientation

import android.content.Context
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Reports the device's physical orientation as a Surface.ROTATION_* constant.
 *
 * Uses [OrientationEventListener] (accelerometer-gravity based), which is
 * reliable across devices. The TYPE_ROTATION_VECTOR path was abandoned: on the
 * reference device its fusion streamed constantly but reported a value that
 * never tracked the phone's pose.
 *
 * A hysteresis gate ensures the reported rotation only flips after it has
 * moved clearly past a sector boundary, so it does not jitter near ~45°.
 *
 * [settledRotation] blocks the caller for at most [timeoutMs], so recording
 * threads are never held indefinitely.
 */
class OrientationManager(
    context: Context
) {

    private val displayRotationProvider: () -> Int = {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.rotation
    }

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(angle: Int) {
            onAngle(angle)
        }
    }

    // Always stores Surface.ROTATION_* constants (0, 1, 2, 3), NOT degrees
    @Volatile
    private var currentRotation = Surface.ROTATION_0

    @Volatile
    private var hasReading = false

    // Debounce state for hysteresis (see onAngle)
    @Volatile
    private var pendingRotation = -1
    @Volatile
    private var pendingCount = 0

    private var started = false

    // TEMP-AUDIT sample counter
    private var sampleCount = 0

    private var firstReadingLatch = CountDownLatch(1)

    fun start() {
        if (started) return
        started = true
        // Discard any previous session's pose so a recording always waits for a
        // fresh reading reflecting how the phone is held RIGHT NOW.
        hasReading = false
        pendingRotation = -1
        pendingCount = 0
        firstReadingLatch = CountDownLatch(1)
        currentRotation = displayRotationProvider()
        listener.enable()
    }

    fun stop() {
        started = false
        try {
            listener.disable()
        } catch (_: Exception) {
        }
    }

    /**
     * Returns the current best-known Surface.ROTATION_* constant without blocking.
     */
    fun getRotation(): Int {
        return if (hasReading) currentRotation else displayRotationProvider()
    }

    /**
     * If no orientation reading has arrived yet, blocks up to [timeoutMs] for
     * the first one; afterwards returns the settled rotation. Falls back to the
     * display rotation if the listener never reports.
     */
    fun settledRotation(timeoutMs: Long = 1200): Int {
        if (!hasReading) {
            try {
                firstReadingLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return if (hasReading) currentRotation else displayRotationProvider()
    }

    private fun onAngle(rawAngle: Int) {
        if (rawAngle == OrientationEventListener.ORIENTATION_UNKNOWN) return
        sampleCount++
        // TEMP-AUDIT: prove the listener is streaming (rate-limited)
        if ((sampleCount and 31) == 1) {
            android.util.Log.i("OrientationManager", "SAMPLE n=$sampleCount angle=$rawAngle rot=$currentRotation")
        }

        val angle = rawAngle.toDouble()
        val candidate = rotationForAngle(angle)

        // Dead-zone: only consider flipping when the angle is clearly inside
        // the candidate's sector (well away from the ±45° boundaries).
        if (!isClearOfBoundary(angle, candidate)) return

        if (candidate == currentRotation) {
            pendingRotation = -1
            pendingCount = 0
            return
        }

        // Debounce: require STABLE_SAMPLES consecutive readings of the new
        // sector before committing the flip.
        if (pendingRotation == candidate) {
            pendingCount++
            if (pendingCount >= STABLE_SAMPLES) {
                currentRotation = candidate
                pendingRotation = -1
                pendingCount = 0
                // TEMP-AUDIT: flip log for single-clip orientation sweep
                android.util.Log.i("OrientationManager", "FLIP rot=$currentRotation n=$sampleCount t=${SystemClock.elapsedRealtime()}")
            }
        } else {
            pendingRotation = candidate
            pendingCount = 1
        }
    }

    /**
     * 0° = natural portrait; 90° = top of device pointing left, etc.
     * Mirrors the OrientationEventListener convention.
     */
    private fun rotationForAngle(angle: Double): Int {
        return when {
            angle >= 315.0 || angle < 45.0 -> Surface.ROTATION_0
            angle < 135.0 -> Surface.ROTATION_90
            angle < 225.0 -> Surface.ROTATION_180
            else -> Surface.ROTATION_270
        }
    }

    /**
     * True when the angle sits at least DEAD_ZONE degrees inside the
     * candidate's sector rather than near a 45°-multiple boundary.
     */
    private fun isClearOfBoundary(angle: Double, rotation: Int): Boolean {
        val center = when (rotation) {
            Surface.ROTATION_90 -> 90.0
            Surface.ROTATION_180 -> 180.0
            Surface.ROTATION_270 -> 270.0
            else -> 0.0
        }
        var delta = abs(angle - center)
        if (delta > 180.0) delta = 360.0 - delta
        return delta >= DEAD_ZONE_DEGREES
    }

    companion object {
        private const val STABLE_SAMPLES = 3
        private const val DEAD_ZONE_DEGREES = 20.0
    }
}