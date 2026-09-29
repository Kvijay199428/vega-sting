package com.vega.sting.recording

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import com.vega.sting.camera.DeviceProfile
import com.vega.sting.camera.DeviceProfileManager
import com.vega.sting.camera.DynamicFallbackEngine
import com.vega.sting.camera.RecordingPreset
import com.vega.sting.camera.VideoPreference
import com.vega.sting.database.AppDatabase
import com.vega.sting.database.CameraSessionTelemetry
import com.vega.sting.orientation.OrientationManager
import com.vega.sting.overlay.OverlayPosition
import com.vega.sting.overlay.OverlayRenderer
import com.vega.sting.overlay.OverlaySettings
import com.vega.sting.settings.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class RecordingManager(private val context: Context) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var overlayRenderer: OverlayRenderer? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var isRecording = false

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var onFinalizeCallback: ((String?) -> Unit)? = null

    private val orientationManager = OrientationManager(context)

    // ── Adaptive Camera Profile System ──────────────────────────────────
    private val profileManager = DeviceProfileManager(context)
    private val telemetryDao = AppDatabase.getDatabase(context).cameraSessionTelemetryDao()
    private val fallbackEngine = DynamicFallbackEngine(telemetryDao)

    /** The preset actually used for the current recording session. */
    private var activePreset: RecordingPreset? = null
    /** Camera ID used for the current session. */
    private var activeCameraId: String? = null
    /** Timestamp when recording started (for telemetry duration). */
    private var recordingStartTime: Long = 0L

    companion object {
        private const val TAG = "RecordingManager"

        // Calibration for the rotation hint. Sweeps confirmed offset 0 is correct on
        // this device (mirrored): PORTRAIT -> 90°, FOLLOW_SENSOR -> 90 - deviceRot.
        private const val ORIENTATION_OFFSET_DEGREES = 0

        fun generateFileName(isAudio: Boolean): String {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-MM-SS", Locale.getDefault()).format(Date())
            val extension = if (isAudio) ".m4a" else ".mp4"
            return "$timestamp$extension"
        }
    }

    @SuppressLint("MissingPermission")
    fun startVideoRecording(
        outputFile: File,
        orientationMode: String,
        onStart: () -> Unit,
        onFinalize: (String?) -> Unit
    ) {
        onFinalizeCallback = onFinalize
        startBackgroundThread()
        orientationManager.start()

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            var cameraIdToUse: String? = null
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraIdToUse = cameraId
                    break
                }
            }
            if (cameraIdToUse == null) {
                cameraIdToUse = cameraManager.cameraIdList.firstOrNull()
            }
            if (cameraIdToUse == null) {
                mainExecutor.execute { onFinalize("No camera found") }
                return
            }

            activeCameraId = cameraIdToUse

            cameraManager.openCamera(cameraIdToUse, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startRecordingSession(camera, outputFile, orientationMode, onStart, onFinalize)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    logTelemetry(success = false, failureReason = "Camera disconnected")
                    mainExecutor.execute { onFinalize("Camera disconnected") }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    logTelemetry(success = false, failureReason = "Camera open error: $error")
                    mainExecutor.execute { onFinalize("Camera open error: $error") }
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            e.printStackTrace()
            logTelemetry(success = false, failureReason = e.message)
            mainExecutor.execute { onFinalize(e.message) }
        }
    }

    private fun startRecordingSession(
        camera: CameraDevice,
        outputFile: File,
        orientationMode: String,
        onStart: () -> Unit,
        onFinalize: (String?) -> Unit
    ) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(camera.id)

            val deviceRotation = when (orientationMode) {

                "PORTRAIT" -> {
                    Surface.ROTATION_0
                }

                "LANDSCAPE" -> {
                    Surface.ROTATION_90
                }

                else -> {
                    // Allow the sensor a brief moment to report before cold-start
                    // recordings; falls back to display rotation if it never does.
                    orientationManager.settledRotation()
                }
            }

            // ── Device-derived rotation metadata (auto-calibrated) ───────
            // STANDARD: MediaRecorder's orientation hint is set once per camera
            // session, so capture a single snapshot and reuse it everywhere.
            val sensorOrientation = characteristics.get(
                CameraCharacteristics.SENSOR_ORIENTATION
            ) ?: 0
            val deviceRotationDegrees = when (deviceRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

            val isFrontFacing = characteristics.get(
                CameraCharacteristics.LENS_FACING
            ) == CameraCharacteristics.LENS_FACING_FRONT

            val rawRotationDegrees = if (isFrontFacing) {
                (sensorOrientation + deviceRotationDegrees) % 360
            } else {
                (sensorOrientation - deviceRotationDegrees + 360) % 360
            }
            val cameraRotationDegrees =
                (rawRotationDegrees + 360 + ORIENTATION_OFFSET_DEGREES) % 360
            Log.i(TAG, "Rotation: sensorOrientation=$sensorOrientation mode=$orientationMode " +
                    "deviceRot=$deviceRotation degrees=$deviceRotationDegrees front=$isFrontFacing " +
                    "raw=$rawRotationDegrees offset=$ORIENTATION_OFFSET_DEGREES hint=$cameraRotationDegrees")

            // ── Adaptive Preset Selection ───────────────────────────────
            val profile = try { profileManager.getProfile() } catch (e: Exception) { null }
            val requestedCodec = loadRequestedCodec()
            val videoPreference = loadVideoPreference()
            val preset = resolvePreset(characteristics, profile, requestedCodec, videoPreference)
            activePreset = preset
            val videoWidth = preset.width
            val videoHeight = preset.height
            Log.i(TAG, "Using adaptive preset: ${videoWidth}x${videoHeight}@${preset.fps} " +
                    "bitrate=${preset.bitrate} codec=${preset.codec} score=${preset.score}")

            val (audioSampleRate, audioChannels) = resolveAudioConfig(profile)

            val overlaySettings = loadOverlaySettings()

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputFile.absolutePath)
                // Adaptive configuration from device profile
                setVideoEncodingBitRate(preset.bitrate)
                setVideoFrameRate(preset.fps)
                setVideoSize(videoWidth, videoHeight)
                setVideoEncoder(
                    if (preset.codec == "HEVC") {
                        MediaRecorder.VideoEncoder.HEVC
                    } else {
                        MediaRecorder.VideoEncoder.H264
                    }
                )
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(audioSampleRate)
                setAudioChannels(audioChannels)
                // Device-derived rotation metadata (auto-calibrated)
                setOrientationHint(cameraRotationDegrees)
                prepare()
            }

            val recorderSurface: Surface = mediaRecorder!!.surface
            overlayRenderer = OverlayRenderer(
                recorderSurface = recorderSurface,
                width = videoWidth,
                height = videoHeight,
                settings = overlaySettings,
                scaledDensity = context.resources.displayMetrics.density * context.resources.configuration.fontScale,
                rotationHint = cameraRotationDegrees,
                renderHandler = backgroundHandler ?: Handler(android.os.Looper.getMainLooper())
            )
            val overlaySurface = overlayRenderer!!.start()
            val surfaces = listOf(overlaySurface)

            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureRequestBuilder.addTarget(overlaySurface)

            // ── Apply Camera2 controls from profile ─────────────────────
            applyAdaptiveControls(captureRequestBuilder, characteristics, preset)

            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                        mediaRecorder?.start()
                        isRecording = true
                        recordingStartTime = System.currentTimeMillis()
                        mainExecutor.execute { onStart() }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        closeCamera()
                        logTelemetry(success = false, failureReason = "Session config error: ${e.message}")
                        mainExecutor.execute { onFinalize("Session config error: ${e.message}") }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    closeCamera()
                    logTelemetry(success = false, failureReason = "Failed to configure capture session")
                    mainExecutor.execute { onFinalize("Failed to configure capture session") }
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            e.printStackTrace()
            closeCamera()
            logTelemetry(success = false, failureReason = e.message)
            mainExecutor.execute { onFinalize(e.message) }
        }
    }

    fun stopRecording() {
        if (isRecording) {
            // BUG2 FIX: abortCaptures() can lock the camera HAL on Android 13/14.
            // Use isolated try-catch blocks for each teardown step.
            try {
                captureSession?.stopRepeating()
            } catch (_: Exception) {}

            try {
                mediaRecorder?.stop()
            } catch (_: Exception) {}

            try {
                captureSession?.close()
            } catch (_: Exception) {}

            isRecording = false
            // Log successful session telemetry
            logTelemetry(success = true, failureReason = null)
            val callback = onFinalizeCallback
            mainExecutor.execute { callback?.invoke(null) }
        }
        orientationManager.stop()
        closeCamera()
        stopBackgroundThread()
    }

    /**
     * BUG3 FIX: Resets recording state when a session fails to finalize.
     * Call this inside onFinalize error branches to prevent poisoned future starts.
     */
    fun reset() {
        isRecording = false
        onFinalizeCallback = null
        activePreset = null
        activeCameraId = null
        recordingStartTime = 0L
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            overlayRenderer?.release()
            overlayRenderer = null
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun loadOverlaySettings(): OverlaySettings {
        return try {
            val settingsManager = SettingsManager(context)
            runBlocking {
                val watermarkEnabled = settingsManager.watermarkEnabled.first()
                OverlaySettings(
                    timestampEnabled = watermarkEnabled && settingsManager.timestampEnabled.first(),
                    timestampPosition = OverlayPosition.from(settingsManager.timestampPosition.first()),
                    timestampFormat = settingsManager.timestampFormat.first(),
                    signature = if (watermarkEnabled) settingsManager.signature.first() else "",
                    signaturePosition = OverlayPosition.from(settingsManager.signaturePosition.first()),
                    textSizeSp = settingsManager.overlayTextSize.first()
                )
            }
        } catch (_: Exception) {
            OverlaySettings()
        }
    }

    // ── Adaptive Camera Profile System ──────────────────────────────────

    /**
     * Resolves the best recording preset using the adaptive profile system.
     * Falls back to safe defaults if the profile is unavailable.
     */
    private fun resolvePreset(
        characteristics: CameraCharacteristics,
        profile: DeviceProfile?,
        requestedCodec: String,
        preference: VideoPreference
    ): RecordingPreset {
        return try {
            if (profile != null) {
                // Use fallback engine which queries telemetry for blacklisted configs
                val preset = runBlocking {
                    fallbackEngine.selectSafePreset(
                        profile = profile,
                        cameraId = activeCameraId ?: "0",
                        preference = preference
                    )
                }
                preset.copy(codec = resolveVideoCodec(profile, requestedCodec))
            } else {
                // Profile not yet generated — fall back to device characteristics
                Log.w(TAG, "No device profile available, using characteristics-based selection")
                selectFromCharacteristics(characteristics, preference)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Adaptive preset selection failed, using safe fallback", e)
            selectFromCharacteristics(characteristics, preference)
        }
    }

    /**
     * Resolves the effective video codec from the user preference intersected
     * with hardware encoder support (auto-calibration). HEVC is only used when a
     * hardware HEVC encoder can do at least 1080p; otherwise we fall back to H.264.
     */
    private fun resolveVideoCodec(
        profile: DeviceProfile,
        requestedCodec: String
    ): String {
        if (requestedCodec != "H.265") return "H264"
        val hasHardwareHevc = profile.encoders.videoEncoders.any { encoder ->
            encoder.mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC &&
                encoder.isHardwareAccelerated &&
                encoder.maxWidth >= 1920 &&
                encoder.maxHeight >= 1080
        }
        if (!hasHardwareHevc) {
            Log.w(TAG, "HEVC requested but no 1080p hardware encoder; falling back to H.264")
        }
        return if (hasHardwareHevc) "HEVC" else "H264"
    }

    /**
     * Derives the audio sample rate and channel count from the scanned AAC
     * encoder capabilities (auto-calibration). Prefers 48 kHz stereo — matching
     * the stock camera — when supported; degrades gracefully on other devices.
     */
    private fun resolveAudioConfig(profile: DeviceProfile?): Pair<Int, Int> {
        val aac = profile
            ?.encoders
            ?.audioEncoders
            ?.firstOrNull { it.mimeType == MediaFormat.MIMETYPE_AUDIO_AAC }
        val sampleRate = pickSampleRate(aac?.sampleRateRanges.orEmpty())
        val channels = if (aac != null && aac.maxChannels >= 2) 2 else 1
        return sampleRate to channels
    }

    private fun pickSampleRate(ranges: List<String>): Int {
        if (ranges.isEmpty()) return 44100
        val preferred = listOf(48000, 44100, 32000, 16000, 8000)
        for (rate in preferred) {
            val supported = ranges.any { range ->
                val parts = range.split("-").mapNotNull { it.trim().toIntOrNull() }
                val low = parts.getOrNull(0) ?: Int.MIN_VALUE
                val high = parts.getOrNull(1) ?: Int.MAX_VALUE
                rate in low..high
            }
            if (supported) return rate
        }
        return preferred.last()
    }

    private fun loadRequestedCodec(): String {
        return try {
            runBlocking { SettingsManager(context).videoCodec.first() }
        } catch (_: Exception) {
            "H.264"
        }
    }

    /**
     * Loads the user's video size/ratio preference plus the device display
     * aspect (max/min) used when AUTO/DISPLAY must resolve to a screen-matching
     * ratio. Falls back to safe defaults when settings are unavailable.
     */
    private fun loadVideoPreference(): VideoPreference {
        val displayAspect = try {
            val w = context.resources.displayMetrics.widthPixels
            val h = context.resources.displayMetrics.heightPixels
            kotlin.math.max(w, h).toFloat() / kotlin.math.max(1, kotlin.math.min(w, h)).toFloat()
        } catch (_: Exception) {
            16f / 9f
        }
        return try {
            runBlocking {
                val settingsManager = SettingsManager(context)
                VideoPreference(
                    resolution = settingsManager.resolution.first(),
                    ratio = settingsManager.videoRatio.first(),
                    displayAspect = displayAspect
                )
            }
        } catch (_: Exception) {
            VideoPreference(displayAspect = displayAspect)
        }
    }

    /**
     * Legacy-compatible fallback: selects video size directly from CameraCharacteristics.
     * Used when the device profile hasn't been scanned yet. Honors the user's
     * size/ratio preference, falling back to the nearest supported size.
     */
    private fun selectFromCharacteristics(
        characteristics: CameraCharacteristics,
        preference: VideoPreference
    ): RecordingPreset {
        val size = selectVideoSize(characteristics, preference)
        val fps = when {
            supportsFps(characteristics, 30) -> 30
            supportsFps(characteristics, 24) -> 24
            else -> 24
        }
        val bitrate = when {
            size.width >= 1920 -> 16_000_000
            size.width >= 1280 -> 8_000_000
            else -> 3_000_000
        }
        return RecordingPreset(
            width = size.width,
            height = size.height,
            fps = fps,
            bitrate = bitrate,
            codec = "H264",
            videoStabilization = false,
            opticalStabilization = false,
            score = 1
        )
    }

    private fun supportsFps(
        characteristics: CameraCharacteristics,
        fps: Int
    ): Boolean {
        val ranges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ) ?: return false
        return ranges.any { it.upper >= fps }
    }

    /**
     * Best-effort selection from CameraCharacteristics (no profile yet).
     * Resolves the preference to one or more target aspects (explicit ratio,
     * else the display ratio, else 16:9) and picks the largest supported size
     * at that ratio within the requested width — stepping down as needed.
     */
    private fun selectVideoSize(
        characteristics: CameraCharacteristics,
        preference: VideoPreference
    ): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedSizes = map
            ?.getOutputSizes(MediaRecorder::class.java)
            ?.toList()
            .orEmpty()

        val requestedWidth = preferredWidthFor(preference.resolution)
        val defaultSize = Size(
            requestedWidth,
            kotlin.math.round(requestedWidth / 16f * 9f).toInt()
        )

        if (supportedSizes.isEmpty()) return defaultSize

        // Candidate aspects in priority order
        val aspectChoices = buildList {
            when (preference.ratio) {
                "16:9" -> add(16f / 9f)
                "4:3" -> add(4f / 3f)
                "1:1" -> add(1f)
                "DISPLAY" -> add(preference.displayAspect)
                else -> {
                    // AUTO: display ratio first, then 16:9
                    add(preference.displayAspect)
                    add(16f / 9f)
                }
            }
        }.distinct()

        for (aspect in aspectChoices) {
            val matches = supportedSizes.filter {
                aspectMatchesRatio(it, aspect) && it.width <= requestedWidth
            }
            if (matches.isNotEmpty()) {
                return matches.maxWithOrNull(compareBy { it.width * it.height }) ?: defaultSize
            }
        }

        // Relaxed step-down: largest supported size within requested width
        val relaxed = supportedSizes.filter { it.width <= requestedWidth }
        return (if (relaxed.isNotEmpty()) relaxed else supportedSizes)
            .maxWithOrNull(compareBy { it.width * it.height })
            ?: defaultSize
    }

    private fun preferredWidthFor(resolution: String): Int {
        return when (resolution.uppercase()) {
            "480P" -> 854
            "720P" -> 1280
            "1440P" -> 2560
            "4K" -> 3840
            "1080P" -> 1920
            else -> 1920 // AUTO / unknown
        }
    }

    private fun aspectMatchesRatio(size: Size, target: Float): Boolean {
        val aspect = size.width.toFloat() / size.height.toFloat()
        val inverted = size.height.toFloat() / size.width.toFloat()
        return kotlin.math.abs(aspect - target) <= 0.05f ||
            kotlin.math.abs(inverted - target) <= 0.05f
    }

    /**
     * Applies Camera2 CaptureRequest controls based on the device profile.
     * Applies stabilization, noise reduction, and scene modes when available.
     */
    private fun applyAdaptiveControls(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        preset: RecordingPreset
    ) {
        // Video stabilization
        if (preset.videoStabilization) {
            val availableVideoStab = characteristics.get(
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
            )
            if (availableVideoStab?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                )
            }
        }

        // Optical stabilization
        if (preset.opticalStabilization) {
            val availableOpticalStab = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
            )
            if (availableOpticalStab?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
            }
        }

        // Noise reduction — use FAST for video recording (low latency)
        val nrModes = characteristics.get(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
        )
        if (nrModes?.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST) == true) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
        }

        // Edge enhancement — FAST for video
        val edgeModes = characteristics.get(
            CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES
        )
        if (edgeModes?.contains(CameraMetadata.EDGE_MODE_FAST) == true) {
            builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
        }

        // Continuous video AF
        val afModes = characteristics.get(
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
        )
        if (afModes?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
    }

    /**
     * Logs a session to the telemetry database for the fallback engine.
     */
    private fun logTelemetry(success: Boolean, failureReason: String?) {
        val preset = activePreset ?: return
        val duration = if (recordingStartTime > 0) {
            System.currentTimeMillis() - recordingStartTime
        } else 0L

        try {
            runBlocking {
                telemetryDao.insert(
                    CameraSessionTelemetry(
                        timestamp = System.currentTimeMillis(),
                        device = Build.MODEL,
                        cameraId = activeCameraId ?: "0",
                        resolution = "${preset.width}x${preset.height}",
                        fps = preset.fps,
                        bitrate = preset.bitrate,
                        codec = preset.codec,
                        stabilization = preset.videoStabilization,
                        extensionMode = null,
                        success = success,
                        failureReason = failureReason,
                        durationMs = duration
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log session telemetry", e)
        }
    }
}
