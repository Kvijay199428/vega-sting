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
import com.vega.sting.camera.resolveEffectiveVideoCodec
import com.vega.sting.database.AppDatabase
import com.vega.sting.database.CameraSessionTelemetry
import com.vega.sting.database.FinalizationReason
import com.vega.sting.orientation.OrientationManager
import com.vega.sting.overlay.OverlayPosition
import com.vega.sting.overlay.OverlayRenderer
import com.vega.sting.overlay.OverlaySettings
import com.vega.sting.settings.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking


data class ActiveRecordingSpec(
    val codecLabel: String,
    val width: Int,
    val height: Int,
    val fps: Int
) {
    val resolutionLabel: String
        get() = "${width}x${height}"

    companion object {
        fun fromPreset(preset: RecordingPreset) = ActiveRecordingSpec(
            codecLabel = if (preset.codec == "HEVC") "H.265" else "H.264",
            width = preset.width,
            height = preset.height,
            fps = preset.fps
        )
    }
}

/** Callbacks the [RecordingService] uses to follow a video session. */
interface VideoRecordingListener {
    fun onSegmentStarted(file: File, partIndex: Int, spec: ActiveRecordingSpec)

    fun onSegmentFinalized(
        file: File,
        partIndex: Int,
        reason: FinalizationReason,
        gapPossible: Boolean
    )

    fun onRecorderError(message: String)

    /** The whole session has ended (after `stopRecording` or an unrecoverable error). */
    fun onRecordingFinalized(reason: FinalizationReason, error: String?)
}

/**
 * Camera2/MediaRecorder video pipeline that can roll over into sequential parts.
 *
 * Rotation is delegated to the platform via [MediaRecorder.setMaxFileSize] and
 * [MediaRecorder.setNextOutputFile], so the camera capture session and the
 * overlay surface stay alive across parts. When the device cannot arm a next
 * output file the pipeline is rebuilt on the same camera device and the segment
 * is flagged as possibly having a gap.
 */
class RecordingManager(private val context: Context) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var overlayRenderer: OverlayRenderer? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var isRecording = false

    private val mainExecutor = ContextCompat.getMainExecutor(context)


    private val orientationManager = OrientationManager(context)


    private val profileManager = DeviceProfileManager(context)
    private val telemetryDao = AppDatabase.getDatabase(context).cameraSessionTelemetryDao()
    private val fallbackEngine = DynamicFallbackEngine(telemetryDao)


    private var activePreset: RecordingPreset? = null

    private var activeCameraId: String? = null

    private var recordingStartTime: Long = 0L

    // ---- Session / segmentation state ---------------------------------------
    private var videoListener: VideoRecordingListener? = null
    private var session: RecordingSession? = null
    private var recordingDirectory: File? = null
    private var segmentLimitBytes: Long = 0L
    private var splitEnabled = false
    private var partIndex = 0
    private var activeSegmentFile: File? = null
    private var seamlessRotation = true
    private var rotationInProgress = false
    private var stoppingSession = false
    private var gapPossible = false

    // Config resolved once and reused for every part of the session.
    private var activeCharacteristics: CameraCharacteristics? = null
    private var resolvedRotationDegrees = 0
    private var resolvedAudioSampleRate = 44100
    private var resolvedAudioChannels = 1
    private var resolvedOverlaySettings: OverlaySettings? = null

    companion object {
        private const val TAG = "RecordingManager"


        private const val ORIENTATION_OFFSET_DEGREES = 0

        fun generateFileName(isAudio: Boolean): String {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-MM-SS", Locale.getDefault()).format(Date())
            val extension = if (isAudio) ".m4a" else ".mp4"
            return "$timestamp$extension"
        }
    }

    @SuppressLint("MissingPermission")
    fun startVideoRecording(
        directory: File,
        session: RecordingSession,
        orientationMode: String,
        segmentLimitBytes: Long,
        splitEnabled: Boolean,
        listener: VideoRecordingListener
    ) {
        this.videoListener = listener
        this.session = session
        this.recordingDirectory = directory
        this.segmentLimitBytes = segmentLimitBytes
        this.splitEnabled = splitEnabled
        this.partIndex = session.startPartIndex.coerceAtLeast(1)
        this.activeSegmentFile = RecordingSessionManager.partFile(directory, session, partIndex)
        this.seamlessRotation = true
        this.rotationInProgress = false
        this.stoppingSession = false
        this.gapPossible = false
        this.recordingStartTime = 0L

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
                fail("No camera found")
                return
            }

            activeCameraId = cameraIdToUse

            cameraManager.openCamera(cameraIdToUse, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    prepareSession(camera, orientationMode)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    logTelemetry(success = false, failureReason = "Camera disconnected")
                    fail("Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    logTelemetry(success = false, failureReason = "Camera open error: $error")
                    fail("Camera open error: $error")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            e.printStackTrace()
            logTelemetry(success = false, failureReason = e.message)
            fail(e.message ?: "Camera open failed")
        }
    }

    /** Resolves rotation/preset/audio/overlay once, then starts the first part. */
    private fun prepareSession(camera: CameraDevice, orientationMode: String) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(camera.id)
            activeCharacteristics = characteristics

            val deviceRotation = when (orientationMode) {
                "PORTRAIT" -> Surface.ROTATION_0
                "LANDSCAPE" -> Surface.ROTATION_90
                else -> orientationManager.settledRotation()
            }

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
            resolvedRotationDegrees = (rawRotationDegrees + 360 + ORIENTATION_OFFSET_DEGREES) % 360
            Log.i(TAG, "Rotation: sensorOrientation=$sensorOrientation mode=$orientationMode " +
                    "deviceRot=$deviceRotation degrees=$deviceRotationDegrees front=$isFrontFacing " +
                    "raw=$rawRotationDegrees offset=$ORIENTATION_OFFSET_DEGREES hint=$resolvedRotationDegrees")

            val profile = try { profileManager.getProfile() } catch (e: Exception) { null }
            val requestedCodec = loadRequestedCodec()
            val videoPreference = loadVideoPreference()
            val preset = resolvePreset(characteristics, profile, requestedCodec, videoPreference)
            activePreset = preset
            Log.i(TAG, "Using adaptive preset: ${preset.width}x${preset.height}@${preset.fps} " +
                    "bitrate=${preset.bitrate} codec=${preset.codec} score=${preset.score}")

            val (audioSampleRate, audioChannels) = resolveAudioConfig(profile)
            resolvedAudioSampleRate = audioSampleRate
            resolvedAudioChannels = audioChannels
            resolvedOverlaySettings = loadOverlaySettings()

            startPipeline(camera)
        } catch (e: Exception) {
            e.printStackTrace()
            logTelemetry(success = false, failureReason = "Session config error: ${e.message}")
            fail("Session config error: ${e.message}")
        }
    }

    /** Builds the recorder + overlay + capture session for [activeSegmentFile]. */
    private fun startPipeline(camera: CameraDevice) {
        val preset = activePreset
        val file = activeSegmentFile
        val characteristics = activeCharacteristics
        if (preset == null || file == null || characteristics == null) {
            fail("Recording pipeline not fully configured")
            return
        }
        try {
            val recorder = createRecorder(preset, file)
            mediaRecorder = recorder

            val recorderSurface: Surface = recorder.surface
            val overlay = OverlayRenderer(
                recorderSurface = recorderSurface,
                width = preset.width,
                height = preset.height,
                settings = resolvedOverlaySettings ?: OverlaySettings(),
                scaledDensity = context.resources.displayMetrics.density * context.resources.configuration.fontScale,
                rotationHint = resolvedRotationDegrees,
                renderHandler = backgroundHandler ?: Handler(android.os.Looper.getMainLooper())
            )
            overlayRenderer = overlay
            val overlaySurface = overlay.start()

            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureRequestBuilder.addTarget(overlaySurface)
            applyAdaptiveControls(captureRequestBuilder, characteristics, preset)

            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(overlaySurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                        recorder.start()
                        isRecording = true
                        if (recordingStartTime == 0L) recordingStartTime = System.currentTimeMillis()
                        val startedFile = activeSegmentFile ?: file
                        val startedIndex = partIndex
                        val spec = ActiveRecordingSpec.fromPreset(preset)
                        mainExecutor.execute {
                            videoListener?.onSegmentStarted(startedFile, startedIndex, spec)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        logTelemetry(success = false, failureReason = "Session config error: ${e.message}")
                        fail("Session config error: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    logTelemetry(success = false, failureReason = "Failed to configure capture session")
                    fail("Failed to configure capture session")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            e.printStackTrace()
            logTelemetry(success = false, failureReason = e.message)
            fail(e.message ?: "Recorder start failed")
        }
    }

    private fun createRecorder(preset: RecordingPreset, file: File): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(file.absolutePath)

            setVideoEncodingBitRate(preset.bitrate)
            setVideoFrameRate(preset.fps)
            setVideoSize(preset.width, preset.height)
            setVideoEncoder(
                if (preset.codec == "HEVC") {
                    MediaRecorder.VideoEncoder.HEVC
                } else {
                    MediaRecorder.VideoEncoder.H264
                }
            )
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(resolvedAudioSampleRate)
            setAudioChannels(resolvedAudioChannels)

            setOrientationHint(resolvedRotationDegrees)
            if (splitEnabled && segmentLimitBytes > 0L) {
                setMaxFileSize(segmentLimitBytes)
                setOnInfoListener { _, what, _ -> handleInfo(what) }
            }
            prepare()
            if (splitEnabled && segmentLimitBytes > 0L) {
                armNextOutputFile(this, partIndex + 1)
            }
        }
        return recorder
    }

    private fun armNextOutputFile(target: MediaRecorder, nextIndex: Int) {
        val dir = recordingDirectory ?: return
        val s = session ?: return
        val nextFile = RecordingSessionManager.partFile(dir, s, nextIndex)
        try {
            target.setNextOutputFile(nextFile)
        } catch (e: Exception) {
            Log.w(TAG, "setNextOutputFile unavailable; video rotation falls back to pipeline restart", e)
            seamlessRotation = false
        }
    }

    private fun handleInfo(what: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                if (!rotationInProgress && !stoppingSession) rotateSeamlessly()
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                // Only rebuild when the current part really is at the limit; a
                // spurious event right after a seamless rollover lands on a
                // freshly opened (near-empty) file and is ignored.
                val grown = segmentLimitBytes > 0L &&
                    (activeSegmentFile?.length() ?: 0L) >= segmentLimitBytes / 2
                if (!rotationInProgress && !stoppingSession && grown) {
                    rotateByRebuild()
                }
            }
        }
    }

    /** The recorder already switched output files by itself. */
    private fun rotateSeamlessly() {
        rotationInProgress = true
        try {
            val finalized = activeSegmentFile
            val finalizedIndex = partIndex
            if (finalized != null) {
                notifyFinalized(finalized, finalizedIndex, FinalizationReason.SIZE_LIMIT)
            }
            partIndex = finalizedIndex + 1
            val dir = recordingDirectory ?: return
            val s = session ?: return
            activeSegmentFile = RecordingSessionManager.partFile(dir, s, partIndex)
            notifyStarted()
            mediaRecorder?.let { if (seamlessRotation) armNextOutputFile(it, partIndex + 1) }
        } finally {
            rotationInProgress = false
        }
    }

    /** Rebuild the pipeline on the same camera device when rotation is unsupported. */
    private fun rotateByRebuild() {
        rotationInProgress = true
        try {
            val finalized = activeSegmentFile
            val finalizedIndex = partIndex
            gapPossible = true
            teardownPipeline()
            if (finalized != null) {
                notifyFinalized(finalized, finalizedIndex, FinalizationReason.SIZE_LIMIT)
            }
            if (stoppingSession) return
            partIndex = finalizedIndex + 1
            val dir = recordingDirectory ?: return
            val s = session ?: return
            activeSegmentFile = RecordingSessionManager.partFile(dir, s, partIndex)
            cameraDevice?.let { startPipeline(it) }
        } finally {
            rotationInProgress = false
        }
    }

    private fun notifyStarted() {
        val file = activeSegmentFile ?: return
        val index = partIndex
        val preset = activePreset ?: return
        val listener = videoListener ?: return
        val spec = ActiveRecordingSpec.fromPreset(preset)
        mainExecutor.execute { listener.onSegmentStarted(file, index, spec) }
    }

    private fun notifyFinalized(file: File, index: Int, reason: FinalizationReason) {
        val gap = gapPossible
        // Capture the listener now: [stopRecording]/[fail] clear the field
        // before this callback runs on the main thread.
        val listener = videoListener ?: return
        mainExecutor.execute { listener.onSegmentFinalized(file, index, reason, gap) }
    }

    fun stopRecording(reason: FinalizationReason = FinalizationReason.MANUAL_STOP) {
        if (stoppingSession) return
        stoppingSession = true

        val finalized = activeSegmentFile
        val finalizedIndex = partIndex

        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {}

        var stoppedCleanly = true
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            stoppedCleanly = false
        }

        try {
            captureSession?.close()
        } catch (_: Exception) {}

        isRecording = false
        logTelemetry(
            success = stoppedCleanly,
            failureReason = if (stoppedCleanly) null else "Recorder stop failed"
        )

        if (finalized != null) {
            val effectiveReason =
                if (stoppedCleanly && finalized.length() > 0L) reason else FinalizationReason.ERROR
            notifyFinalized(finalized, finalizedIndex, effectiveReason)
        }

        orientationManager.stop()
        closeCamera()
        stopBackgroundThread()

        val listener = videoListener
        val error = if (stoppedCleanly) null else "Recorder stop failed"
        mainExecutor.execute { listener?.onRecordingFinalized(reason, error) }

        videoListener = null
        session = null
        activeSegmentFile = null
    }

    private fun teardownPipeline() {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {}
        try {
            captureSession?.close()
        } catch (_: Exception) {}
        captureSession = null
        overlayRenderer?.release()
        overlayRenderer = null
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        isRecording = false
    }

    /** Reports an unrecoverable error and tears the session down. */
    private fun fail(message: String) {
        val listener = videoListener
        val finalized = activeSegmentFile
        val finalizedIndex = partIndex
        mainExecutor.execute { listener?.onRecorderError(message) }
        closeCamera()
        stopBackgroundThread()
        orientationManager.stop()
        if (finalized != null && finalized.length() > 0L) {
            notifyFinalized(finalized, finalizedIndex, FinalizationReason.ERROR)
        }
        mainExecutor.execute { listener?.onRecordingFinalized(FinalizationReason.ERROR, message) }
        videoListener = null
        session = null
        activeSegmentFile = null
    }

    fun reset() {
        isRecording = false
        videoListener = null
        session = null
        activeSegmentFile = null
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

    private fun resolvePreset(
        characteristics: CameraCharacteristics,
        profile: DeviceProfile?,
        requestedCodec: String,
        preference: VideoPreference
    ): RecordingPreset {
        return try {
            if (profile != null) {
                val preset = runBlocking {
                    fallbackEngine.selectSafePreset(
                        profile = profile,
                        cameraId = activeCameraId ?: "0",
                        preference = preference
                    )
                }
                preset.copy(codec = resolveVideoCodec(profile, requestedCodec))
            } else {
                Log.w(TAG, "No device profile available, using characteristics-based selection")
                selectFromCharacteristics(characteristics, preference)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Adaptive preset selection failed, using safe fallback", e)
            selectFromCharacteristics(characteristics, preference)
        }
    }

    private fun resolveVideoCodec(
        profile: DeviceProfile,
        requestedCodec: String
    ): String {
        val codec = resolveEffectiveVideoCodec(requestedCodec, profile.encoders.videoEncoders)
        if (requestedCodec == "H.265" && codec != "HEVC") {
            Log.w(TAG, "HEVC requested but no 1080p hardware encoder; falling back to H.264")
        }
        return codec
    }

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

        val aspectChoices = buildList {
            when (preference.ratio) {
                "16:9" -> add(16f / 9f)
                "4:3" -> add(4f / 3f)
                "1:1" -> add(1f)
                "DISPLAY" -> add(preference.displayAspect)
                else -> {
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
            else -> 1920
        }
    }

    private fun aspectMatchesRatio(size: Size, target: Float): Boolean {
        val aspect = size.width.toFloat() / size.height.toFloat()
        val inverted = size.height.toFloat() / size.width.toFloat()
        return kotlin.math.abs(aspect - target) <= 0.05f ||
            kotlin.math.abs(inverted - target) <= 0.05f
    }

    private fun applyAdaptiveControls(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        preset: RecordingPreset
    ) {
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

        val nrModes = characteristics.get(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
        )
        if (nrModes?.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST) == true) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
        }

        val edgeModes = characteristics.get(
            CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES
        )
        if (edgeModes?.contains(CameraMetadata.EDGE_MODE_FAST) == true) {
            builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
        }

        val afModes = characteristics.get(
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
        )
        if (afModes?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
    }

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
