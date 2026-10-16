package com.vega.sting.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.vega.sting.database.FinalizationReason
import com.vega.sting.recording.ActiveRecordingSpec
import com.vega.sting.recording.RecordingSession
import com.vega.sting.recording.RecordingSessionManager
import com.vega.sting.recording.VideoRecordingListener
import java.io.File

/**
 * Records the composited dual-camera view into a single MP4.
 *
 * The two camera streams are combined by [DualCameraCompositor] *before*
 * encoding: the compositor renders into one MediaRecorder input surface, and a
 * single microphone track is muxed alongside it. Two independent recordings are
 * never produced and never merged afterwards.
 *
 * File splitting reuses MediaRecorder's own rotation
 * ([MediaRecorder.setNextOutputFile]); when a device cannot arm a next output
 * file the pipeline is rebuilt on the same camera devices and the new segment
 * is flagged as possibly having a gap.
 */
class DualRecordingPipeline(private val context: Context) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var compositor: DualCameraCompositor? = null
    private var recorder: MediaRecorder? = null

    private var frontDevice: CameraDevice? = null
    private var backDevice: CameraDevice? = null
    private var frontSession: CameraCaptureSession? = null
    private var backSession: CameraCaptureSession? = null
    private var frontInputSurface: Surface? = null
    private var backInputSurface: Surface? = null
    private var frontSourceSize: Size = Size(1280, 720)
    private var backSourceSize: Size = Size(1280, 720)
    private var frontConfigured = false
    private var backConfigured = false

    private var listener: VideoRecordingListener? = null
    private var session: RecordingSession? = null
    private var directory: File? = null
    private var activeSegmentFile: File? = null
    private var partIndex = 1
    private var segmentLimitBytes = 0L
    private var splitEnabled = false
    private var seamlessRotation = true
    private var rotationInProgress = false
    private var stopping = false
    private var gapPossible = false
    private var started = false

    private var config: DualCameraConfig = DualCameraConfig.DEFAULTS
    private var portraitOutput = true
    private var outputWidth = 720
    private var outputHeight = 1280
    private var capability: DualCameraCapabilityResult? = null

    private val mainHandler by lazy { Handler(context.mainLooper) }

    fun isRunning(): Boolean = started

    @SuppressLint("MissingPermission")
    fun startVideoRecording(
        directory: File,
        session: RecordingSession,
        config: DualCameraConfig,
        portraitOutput: Boolean,
        segmentLimitBytes: Long,
        splitEnabled: Boolean,
        listener: VideoRecordingListener
    ) {
        this.listener = listener
        this.session = session
        this.directory = directory
        this.config = config
        this.portraitOutput = portraitOutput
        this.segmentLimitBytes = segmentLimitBytes
        this.splitEnabled = splitEnabled
        this.partIndex = session.startPartIndex.coerceAtLeast(1)
        this.activeSegmentFile = RecordingSessionManager.partFile(directory, session, partIndex)
        this.seamlessRotation = true
        this.rotationInProgress = false
        this.stopping = false
        this.gapPossible = false
        this.started = false

        outputWidth = config.quality.width(portraitOutput)
        outputHeight = config.quality.height(portraitOutput)

        val bg = HandlerThread("DualRecorder").also { it.start() }
        thread = bg
        val h = Handler(bg.looper)
        handler = h

        h.post { beginOnThread() }
    }

    @SuppressLint("MissingPermission")
    private fun beginOnThread() {
        val h = handler ?: return
        val result = DualCameraCapabilityChecker(context).check()
        capability = result
        if (!result.isReady || result.frontCameraId == null || result.backCameraId == null) {
            fail(result.reason ?: "Dual camera is not supported on this device.")
            return
        }

        backSourceSize = selectSourceSize(result.previewSizesBack)
        frontSourceSize = selectSourceSize(result.previewSizesFront)

        val compositor = DualCameraCompositor(h)
        this.compositor = compositor
        compositor.setConfig(config, portraitOutput)
        compositor.setRenderingEnabled(false)

        startPipeline()
    }

    private fun selectSourceSize(sizes: List<Size>): Size {
        if (sizes.isEmpty()) return Size(1280, 720)
        val landscape = sizes.filter { it.width >= it.height }
        val pool = landscape.ifEmpty { sizes }
        return pool.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: pool.firstOrNull { it.width <= 1920 && it.width >= 640 }
            ?: pool.minByOrNull { it.width.toLong() * it.height }
            ?: pool.first()
    }

    /** Builds recorder + compositor + both capture sessions for the current segment. */
    @SuppressLint("MissingPermission")
    private fun startPipeline() {
        val h = handler ?: return
        val file = activeSegmentFile ?: run { fail("No output file"); return }
        val compositor = compositor ?: run { fail("No compositor"); return }
        try {
            val rec = createRecorder(file)
            recorder = rec
            val recorderSurface = rec.surface

            if (!compositor.initialize(recorderSurface, outputWidth, outputHeight)) {
                fail("Could not attach the compositor output.")
                return
            }
            compositor.setConfig(config, portraitOutput)
            compositor.setRenderingEnabled(false)

            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val result = capability ?: run { fail("No capability result"); return }
            val backId = result.backCameraId ?: return
            val frontId = result.frontCameraId ?: return

            backInputSurface = compositor.createInputSurface(DualCameraRole.BACK, backSourceSize.width, backSourceSize.height)
            frontInputSurface = compositor.createInputSurface(DualCameraRole.FRONT, frontSourceSize.width, frontSourceSize.height)

            frontConfigured = false
            backConfigured = false

            // On a mid-session rebuild the camera devices are still open (only
            // the sessions were closed), so we must not call openCamera again;
            // we simply configure fresh sessions on the existing devices.
            val existingBack = backDevice
            val existingFront = frontDevice
            if (existingBack != null && existingFront != null) {
                configureSession(existingBack, DualCameraRole.BACK)
                configureSession(existingFront, DualCameraRole.FRONT)
            } else {
                manager.openCamera(backId, deviceCallback(DualCameraRole.BACK), h)
                manager.openCamera(frontId, deviceCallback(DualCameraRole.FRONT), h)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startPipeline failed", e)
            fail(e.message ?: "Could not start the dual-camera pipeline.")
        }
    }

    private fun deviceCallback(role: DualCameraRole): CameraDevice.StateCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (role == DualCameraRole.BACK) backDevice = camera else frontDevice = camera
                configureSession(camera, role)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (role == DualCameraRole.BACK) backDevice = null else frontDevice = null
                fail("${role.name.lowercase()} camera disconnected.")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (role == DualCameraRole.BACK) backDevice = null else frontDevice = null
                fail("${role.name.lowercase()} camera error: $error")
            }
        }

    private fun configureSession(camera: CameraDevice, role: DualCameraRole) {
        val h = handler ?: return
        val target = if (role == DualCameraRole.BACK) backInputSurface else frontInputSurface
        if (target == null) {
            fail("Missing camera input surface.")
            return
        }
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(target)
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            camera.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (role == DualCameraRole.BACK) backSession = session else frontSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, h)
                    } catch (e: Exception) {
                        fail("Failed to start the ${role.name.lowercase()} stream.")
                        return
                    }
                    if (role == DualCameraRole.BACK) backConfigured = true else frontConfigured = true
                    if (frontConfigured && backConfigured) onStreamsReady()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    fail("Failed to configure the ${role.name.lowercase()} capture session.")
                }
            }, h)
        } catch (e: Exception) {
            fail("Failed to configure the ${role.name.lowercase()} camera.")
        }
    }

    private fun onStreamsReady() {
        if (started) {
            // A rebuild: recorder is already the fresh one; just resume.
            compositor?.setRenderingEnabled(true)
            return
        }
        try {
            recorder?.start()
            compositor?.setRenderingEnabled(true)
            started = true
            val file = activeSegmentFile ?: return
            val index = partIndex
            val spec = ActiveRecordingSpec(
                codecLabel = "H.264",
                width = outputWidth,
                height = outputHeight,
                fps = config.fps
            )
            mainHandler.post { listener?.onSegmentStarted(file, index, spec) }
        } catch (e: Exception) {
            Log.e(TAG, "recorder.start failed", e)
            fail(e.message ?: "Recorder failed to start.")
        }
    }

    private fun createRecorder(file: File): MediaRecorder {
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(file.absolutePath)
            setVideoEncodingBitRate(bitrateFor(config.quality))
            setVideoFrameRate(config.fps)
            setVideoSize(outputWidth, outputHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioChannels(1)
            // The compositor renders the frame already upright, so no rotation hint.
            setOrientationHint(0)
            if (splitEnabled && segmentLimitBytes > 0L) {
                setMaxFileSize(segmentLimitBytes)
                setOnInfoListener { _, what, _ -> onRecorderInfo(what) }
            }
            prepare()
            if (splitEnabled && segmentLimitBytes > 0L) {
                armNextOutputFile(this, partIndex + 1)
            }
        }
        return rec
    }

    private fun bitrateFor(quality: DualCameraQuality): Int = when (quality) {
        DualCameraQuality.Q_720P -> 10_000_000
        DualCameraQuality.Q_1080P -> 16_000_000
        DualCameraQuality.Q_4K -> 40_000_000
    }

    private fun armNextOutputFile(target: MediaRecorder, nextIndex: Int) {
        val dir = directory ?: return
        val s = session ?: return
        try {
            target.setNextOutputFile(RecordingSessionManager.partFile(dir, s, nextIndex))
        } catch (e: Exception) {
            Log.w(TAG, "setNextOutputFile unavailable; splitting falls back to pipeline rebuild", e)
            seamlessRotation = false
        }
    }

    private fun onRecorderInfo(what: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED ->
                if (!rotationInProgress && !stopping) rotateSeamlessly()

            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                val grown = segmentLimitBytes > 0L &&
                    (activeSegmentFile?.length() ?: 0L) >= segmentLimitBytes / 2
                if (!rotationInProgress && !stopping && grown) rotateByRebuild()
            }
        }
    }

    private fun rotateSeamlessly() {
        rotationInProgress = true
        try {
            val finalized = activeSegmentFile
            val finalizedIndex = partIndex
            if (finalized != null) notifyFinalized(finalized, finalizedIndex, FinalizationReason.SIZE_LIMIT)
            partIndex = finalizedIndex + 1
            val dir = directory ?: return
            val s = session ?: return
            activeSegmentFile = RecordingSessionManager.partFile(dir, s, partIndex)
            notifyStarted()
            recorder?.let { if (seamlessRotation) armNextOutputFile(it, partIndex + 1) }
        } finally {
            rotationInProgress = false
        }
    }

    private fun rotateByRebuild() {
        rotationInProgress = true
        try {
            val finalized = activeSegmentFile
            val finalizedIndex = partIndex
            gapPossible = true
            teardownPipeline(keepDevices = true)
            if (stopping) return
            if (finalized != null) notifyFinalized(finalized, finalizedIndex, FinalizationReason.SIZE_LIMIT)
            partIndex = finalizedIndex + 1
            val dir = directory ?: return
            val s = session ?: return
            activeSegmentFile = RecordingSessionManager.partFile(dir, s, partIndex)
            started = false
            startPipeline()
        } finally {
            rotationInProgress = false
        }
    }

    private fun notifyStarted() {
        val file = activeSegmentFile ?: return
        val index = partIndex
        val spec = ActiveRecordingSpec("H.264", outputWidth, outputHeight, config.fps)
        mainHandler.post { listener?.onSegmentStarted(file, index, spec) }
    }

    private fun notifyFinalized(file: File, index: Int, reason: FinalizationReason) {
        val gap = gapPossible
        val l = listener ?: return
        mainHandler.post { l.onSegmentFinalized(file, index, reason, gap) }
    }

    fun stopRecording(reason: FinalizationReason = FinalizationReason.MANUAL_STOP) {
        handler?.post { stopOnThread(reason) } ?: run { stopOnThread(reason) }
    }

    private fun stopOnThread(reason: FinalizationReason) {
        if (stopping) return
        stopping = true
        started = false

        val finalized = activeSegmentFile
        val finalizedIndex = partIndex

        var stoppedCleanly = true
        try {
            frontSession?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            backSession?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            recorder?.stop()
        } catch (e: Exception) {
            stoppedCleanly = false
        }

        closeAll()

        if (finalized != null) {
            val effectiveReason =
                if (stoppedCleanly && finalized.length() > 0L) reason else FinalizationReason.ERROR
            notifyFinalized(finalized, finalizedIndex, effectiveReason)
        }

        val l = listener
        val error = if (stoppedCleanly) null else "Recorder stop failed"
        mainHandler.post { l?.onRecordingFinalized(reason, error) }

        listener = null
        session = null
        activeSegmentFile = null
    }

    /** Tears down recorder/sessions/compositor output (optionally keeping cameras). */
    private fun teardownPipeline(keepDevices: Boolean) {
        try {
            recorder?.setOnInfoListener(null)
        } catch (_: Exception) {
        }
        // Stop first so the MP4 box is finalised; a recorder that never started
        // (or was already stopped) simply throws and is caught.
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null

        try {
            frontSession?.close()
        } catch (_: Exception) {
        }
        try {
            backSession?.close()
        } catch (_: Exception) {
        }
        frontSession = null
        backSession = null

        frontInputSurface?.let { runCatching { it.release() } }
        backInputSurface?.let { runCatching { it.release() } }
        frontInputSurface = null
        backInputSurface = null
        frontConfigured = false
        backConfigured = false

        // Drop the compositor's own SurfaceTextures too, so a rebuild creates
        // fresh inputs instead of reusing released ones.
        compositor?.releaseInputs()
        compositor?.setRenderingEnabled(false)
        if (!keepDevices) {
            compositor?.release()
            compositor = null
        }
    }

    private fun closeAll() {
        teardownPipeline(keepDevices = false)
        try {
            frontDevice?.close()
        } catch (_: Exception) {
        }
        try {
            backDevice?.close()
        } catch (_: Exception) {
        }
        frontDevice = null
        backDevice = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun fail(message: String) {
        val finalized = activeSegmentFile
        val finalizedIndex = partIndex
        mainHandler.post { listener?.onRecorderError(message) }
        started = false
        closeAll()
        if (finalized != null && finalized.length() > 0L) {
            mainHandler.post { listener?.onSegmentFinalized(finalized, finalizedIndex, FinalizationReason.ERROR, gapPossible) }
        }
        mainHandler.post { listener?.onRecordingFinalized(FinalizationReason.ERROR, message) }
        listener = null
        session = null
        activeSegmentFile = null
    }

    fun release() {
        handler?.post { closeAll() } ?: closeAll()
    }

    companion object {
        private const val TAG = "DualRecordingPipeline"
    }
}
