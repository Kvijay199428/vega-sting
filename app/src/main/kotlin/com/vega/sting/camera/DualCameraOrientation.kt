package com.vega.sting.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.Surface
import com.vega.sting.orientation.OrientationManager

/**
 * Resolves the locked orientation policy for one dual-camera session so the live
 * preview and the recorded composite apply exactly the same per-feed rotation.
 *
 * The device-rotation source is identical to the single-camera recorder: the
 * explicit `PORTRAIT`/`LANDSCAPE` modes pin the rotation, and `FOLLOW_SENSOR`
 * uses the settled accelerometer reading from [OrientationManager] — never the
 * Activity configuration, which can disagree with how the phone is actually
 * held (locked orientation, split screen, stale configuration).
 *
 * The per-feed rotation for a sensor is produced by [RotationResolver], the same
 * number the single-camera path feeds to `MediaRecorder.setOrientationHint`, so
 * dual mode cannot diverge from normal recording.
 */
object DualCameraOrientation {

    /**
     * Extra degrees added to the FRONT sensor orientation before the rotation is
     * resolved.
     *
     * Device-verified on the tested hardware: the front stream is 180° from what
     * `SENSOR_ORIENTATION` says (a front sensor reported as 270 behaves as 90).
     * The rear feed is unaffected.
     */
    const val FRONT_FEED_EXTRA_DEGREES = 180

    /**
     * Clockwise degrees that make the FRONT feed upright.
     *
     * A front camera is normally resolved with the *mirrored* convention
     * (`sensor + deviceRotation`), because a mirror negates the rotation. The
     * compositor draws this feed **unmirrored** by default ([DualCameraConfig.mirrorFront]
     * only flips the quad in screen space, independently of the rotation), so that
     * mirror cancels and the *rear* convention (`sensor - deviceRotation`) applies.
     * Using the mirrored convention was the original defect: its error grew with
     * `2 × deviceRotation`, so it was 180° out in portrait and correct in
     * landscape.
     *
     * Combined with [FRONT_FEED_EXTRA_DEGREES] this is the rear-style rotation for
     * a sensor mounted 180° from the reported value:
     *
     *     (sensor + 180 - deviceRotation) mod 360
     *
     * Pure, so it can be unit tested on the JVM. Verified on device: sensor 270
     * gives 90 in portrait (device rotation 0) and 0 in landscape (90).
     */
    fun frontFeedRotationDegrees(sensorOrientation: Int, deviceRotationDegrees: Int): Int {
        val raw = sensorOrientation + FRONT_FEED_EXTRA_DEGREES - deviceRotationDegrees
        return (raw % 360 + 360) % 360
    }

    /** Everything the compositor needs to orient one session. */
    data class Session(
        /** The composite canvas is upright in portrait rather than landscape. */
        val portraitOutput: Boolean,
        val deviceRotationDegrees: Int,
        /** Clockwise degrees to apply to the front feed so it is upright. */
        val frontRotationDegrees: Int,
        /** Clockwise degrees to apply to the back feed so it is upright. */
        val backRotationDegrees: Int,
        val frontSensorOrientation: Int,
        val backSensorOrientation: Int
    )

    /** Resolves the device posture in degrees, matching [com.vega.sting.recording.RecordingManager]. */
    fun resolveDeviceRotationDegrees(
        orientationMode: String,
        orientationManager: OrientationManager
    ): Int {
        val rotation = when (orientationMode) {
            "PORTRAIT" -> Surface.ROTATION_0
            "LANDSCAPE" -> Surface.ROTATION_90
            else -> orientationManager.settledRotation()
        }
        return RotationResolver.surfaceRotationToDegrees(rotation)
    }

    /**
     * Reads both cameras' sensor orientation and resolves the session policy.
     * Must be called off the main thread: [OrientationManager.settledRotation]
     * may block briefly for the first accelerometer reading.
     */
    fun resolve(
        context: Context,
        orientationMode: String,
        frontCameraId: String,
        backCameraId: String,
        orientationManager: OrientationManager
    ): Session {
        val deviceRotationDegrees = resolveDeviceRotationDegrees(orientationMode, orientationManager)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val frontChars = manager.getCameraCharacteristics(frontCameraId)
        val backChars = manager.getCameraCharacteristics(backCameraId)

        val frontSensor = frontChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val backSensor = backChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val frontIsFront =
            frontChars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val backIsFront =
            backChars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

        val frontRotation = if (frontIsFront) {
            // The front feed is unmirrored in the compositor, so it needs the
            // rear-style rotation (see frontFeedRotationDegrees). The rear feed
            // below must not change.
            frontFeedRotationDegrees(frontSensor, deviceRotationDegrees)
        } else {
            RotationResolver.resolveDegrees(frontSensor, isFrontFacing = false, deviceRotationDegrees)
        }
        val backRotation = RotationResolver.resolveDegrees(backSensor, backIsFront, deviceRotationDegrees)
        val portrait = RotationResolver.isPortraitPosture(deviceRotationDegrees)

        Log.i(
            TAG,
            "Dual orientation: mode=$orientationMode deviceRot=$deviceRotationDegrees " +
                "frontSensor=$frontSensor frontIsFront=$frontIsFront frontRot=$frontRotation " +
                "frontCorrection=${if (frontIsFront) FRONT_FEED_EXTRA_DEGREES else 0} " +
                "backSensor=$backSensor backIsFront=$backIsFront backRot=$backRotation portrait=$portrait"
        )

        return Session(
            portraitOutput = portrait,
            deviceRotationDegrees = deviceRotationDegrees,
            frontRotationDegrees = frontRotation,
            backRotationDegrees = backRotation,
            frontSensorOrientation = frontSensor,
            backSensorOrientation = backSensor
        )
    }

    private const val TAG = "DualOrientation"
}
