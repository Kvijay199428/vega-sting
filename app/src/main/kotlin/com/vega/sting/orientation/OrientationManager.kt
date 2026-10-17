package com.vega.sting.orientation

import android.content.Context
import android.view.OrientationEventListener
import android.view.WindowManager

/**
 * Reports the device's physical orientation as a Surface.ROTATION_* constant.
 *
 * Uses [OrientationEventListener] (accelerometer-gravity based), which is
 * reliable across devices. The TYPE_ROTATION_VECTOR path was abandoned: on the
 * reference device its fusion streamed constantly but reported a value that
 * never tracked the phone's pose.
 *
 * The angle → rotation mapping and the hysteresis gate live in
 * [OrientationState] (pure, unit-tested); the first-reading latch lives in
 * [ReadingGate]. This class only wires the Android listener to them.
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

    private val state = OrientationState()
    private val gate = ReadingGate()

    private var started = false

    fun start() {
        if (started) return
        started = true
        // Discard any previous session's pose so a recording always waits for a
        // fresh reading reflecting how the phone is held RIGHT NOW.
        state.reset(displayRotationProvider())
        gate.reset()
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
        return if (state.hasReading) state.rotation else displayRotationProvider()
    }

    /**
     * If no orientation reading has arrived yet, blocks up to [timeoutMs] for
     * the first one; afterwards returns the settled rotation. Falls back to the
     * display rotation if the listener never reports.
     */
    fun settledRotation(timeoutMs: Long = 1200): Int {
        if (!state.hasReading) {
            gate.await(timeoutMs)
        }
        return if (state.hasReading) state.rotation else displayRotationProvider()
    }

    private fun onAngle(rawAngle: Int) {
        if (rawAngle == OrientationEventListener.ORIENTATION_UNKNOWN) return
        if (state.onAngle(rawAngle)) {
            gate.signal()
        }
    }
}
