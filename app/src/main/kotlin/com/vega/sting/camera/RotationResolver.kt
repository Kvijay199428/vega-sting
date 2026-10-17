package com.vega.sting.camera

import android.view.Surface

/**
 * Single source of truth for camera-to-output rotation, shared by the
 * single-camera recorder ([com.vega.sting.recording.RecordingManager]) and the
 * dual-camera compositor ([DualCameraCompositor]) so the two pipelines can never
 * drift apart.
 *
 * [resolveDegrees] returns the clockwise rotation that turns a camera's
 * sensor-native frames into an upright image. The single-camera pipeline passes
 * the number to [android.media.MediaRecorder.setOrientationHint]; the dual
 * pipeline instead bakes an equivalent rotation into each feed and keeps the
 * hint at 0, so the number must stay identical between the two paths.
 *
 * The maths is deliberately free of GL/EGL types so it can be unit tested on the
 * JVM and reused verbatim.
 */
object RotationResolver {

    /**
     * App-wide correction applied on top of the sensor/device rotation. Kept at
     * 0 today; a device that genuinely reports a different mount angle would
     * change this single constant rather than either pipeline.
     */
    const val ORIENTATION_OFFSET_DEGREES = 0

    /** Maps a [Surface] ROTATION_* constant (or an already-degrees value) to degrees. */
    fun surfaceRotationToDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /**
     * The clockwise rotation, in degrees, that makes the frames from a camera
     * with the given [sensorOrientation] and lens facing display upright for a
     * device posture of [deviceRotationDegrees].
     *
     * A front-facing camera adds the device rotation (its geometry is the mirror
     * image of a rear camera, which halves the correction); a rear-facing camera
     * subtracts it. The result is normalised to `[0, 360)` and [offsetDegrees] is
     * applied exactly once.
     */
    fun resolveDegrees(
        sensorOrientation: Int,
        isFrontFacing: Boolean,
        deviceRotationDegrees: Int,
        offsetDegrees: Int = ORIENTATION_OFFSET_DEGREES
    ): Int {
        val raw = if (isFrontFacing) {
            (sensorOrientation + deviceRotationDegrees) % 360
        } else {
            (sensorOrientation - deviceRotationDegrees + 360) % 360
        }
        return (raw + offsetDegrees % 360 + 360) % 360
    }

    /** True when a device rotation of [deviceRotationDegrees] is a portrait hold. */
    fun isPortraitPosture(deviceRotationDegrees: Int): Boolean =
        deviceRotationDegrees % 360 == 0 || deviceRotationDegrees % 360 == 180
}
