package com.vega.sting.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.vega.sting.orientation.OrientationManager
import com.vega.sting.overlay.OverlayLayer
import com.vega.sting.overlay.OverlaySettingsLoader

/**
 * Owns the two live Camera2 devices used for the dual-camera preview.
 *
 * Both cameras are opened on a single serialized handler thread that is also
 * the compositor's GL thread, so camera callbacks and GL never race. Nothing
 * here claims success by setting a flag: the controller only reports
 * [State.PREVIEWING] once both capture sessions are actually configured and
 * have been asked for repeating frames.
 *
 * A failed second camera closes the first, so the device is never left in a
 * half-open state.
 */
class DualCameraController(private val context: Context) {

    enum class State { IDLE, CHECKING, STARTING, PREVIEWING, UNSUPPORTED, ERROR }

    interface Listener {
        fun onState(state: State, message: String?)
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var compositor: DualCameraCompositor? = null
    private var overlayLayer: OverlayLayer? = null
    private var frontDevice: CameraDevice? = null
    private var backDevice: CameraDevice? = null
    private var frontSession: CameraCaptureSession? = null
    private var backSession: CameraCaptureSession? = null
    private var frontConfigured = false
    private var backConfigured = false
    private var listener: Listener? = null
    private var capability: DualCameraCapabilityResult? = null
    private var config: DualCameraConfig = DualCameraConfig.DEFAULTS
    private var orientationMode: String = "FOLLOW_SENSOR"
    private var portraitOutput = true
    private var frontRotationDegrees = 0
    private var backRotationDegrees = 0
    private var running = false

    private val orientationManager = OrientationManager(context)

    val capabilityResult: DualCameraCapabilityResult? get() = capability

    /** Starts the live preview. Must be called from the main thread. */
    @SuppressLint("MissingPermission")
    fun startPreview(
        config: DualCameraConfig,
        orientationMode: String,
        previewSurface: Surface,
        viewWidth: Int,
        viewHeight: Int,
        listener: Listener
    ) {
        if (running) return
        this.config = config
        this.orientationMode = orientationMode
        this.listener = listener
        this.portraitOutput = DualCameraGeometry.isPortraitOutput(viewWidth, viewHeight)
        running = true
        publish(State.CHECKING, null)

        val bg = HandlerThread("DualCameraPreview").also { it.start() }
        thread = bg
        val h = Handler(bg.looper)
        handler = h

        h.post {
            val result = DualCameraCapabilityChecker(context).check()
            capability = result
            if (!result.isReady || result.frontCameraId == null || result.backCameraId == null) {
                publish(State.UNSUPPORTED, result.reason ?: "Dual camera is not supported on this device.")
                stopInternal()
                return@post
            }
            // Resolve the session orientation the same way the recorder does so
            // the preview shows exactly what will be written to the file.
            orientationManager.start()
            val session = try {
                DualCameraOrientation.resolve(
                    context = context,
                    orientationMode = orientationMode,
                    frontCameraId = result.frontCameraId,
                    backCameraId = result.backCameraId,
                    orientationManager = orientationManager
                )
            } catch (e: Exception) {
                publish(State.ERROR, "Could not resolve the preview orientation: ${e.message}")
                stopInternal()
                return@post
            }
            portraitOutput = session.portraitOutput
            frontRotationDegrees = session.frontRotationDegrees
            backRotationDegrees = session.backRotationDegrees

            val compositor = DualCameraCompositor(h)
            this.compositor = compositor
            if (!compositor.initialize(previewSurface, viewWidth, viewHeight)) {
                publish(State.ERROR, "Could not initialise the compositor.")
                stopInternal()
                return@post
            }
            applyCompositorConfig(compositor)
            attachOverlay(compositor, viewWidth, viewHeight)
            publish(State.STARTING, null)
            openCameras(result)
        }
    }

    private fun applyCompositorConfig(compositor: DualCameraCompositor) {
        compositor.setConfig(config, portraitOutput)
        compositor.setFeedRotation(frontRotationDegrees, backRotationDegrees)
    }

    /**
     * Builds and attaches the watermark overlay so the live preview is
     * WYSIWYG: it shows exactly the timestamp/signature the recorder will bake
     * in. Must run on the render thread with the compositor's GL context
     * current. The composite is already upright, hence rotation hint 0.
     */
    private fun attachOverlay(compositor: DualCameraCompositor, width: Int, height: Int) {
        overlayLayer?.release()
        val layer = OverlayLayer(
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
            settings = OverlaySettingsLoader.load(context),
            scaledDensity = context.resources.displayMetrics.density *
                context.resources.configuration.fontScale,
            rotationHint = 0
        )
        overlayLayer = layer
        compositor.setOverlayLayer(layer)
    }

    fun updateConfig(newConfig: DualCameraConfig) {
        config = newConfig
        handler?.post {
            compositor?.let {
                applyCompositorConfig(it)
                it.renderFrame()
            }
        }
    }

    fun updatePreviewSurface(surface: Surface, width: Int, height: Int) {
        handler?.post {
            val c = compositor ?: return@post
            c.bindOutput(surface, width, height)
            applyCompositorConfig(c)
            // The overlay is authored for the exact surface size, so it must be
            // rebuilt whenever the preview surface is re-sized.
            attachOverlay(c, width, height)
            c.renderFrame()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameras(result: DualCameraCapabilityResult) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val h = handler ?: return
        val backId = result.backCameraId ?: return
        val frontId = result.frontCameraId ?: return

        val backSize = selectSourceSize(result.previewSizesBack)
        val frontSize = selectSourceSize(result.previewSizesFront)
        val compositor = this.compositor ?: return

        backInputSurface = compositor.createInputSurface(DualCameraRole.BACK, backSize.width, backSize.height)
        frontInputSurface = compositor.createInputSurface(DualCameraRole.FRONT, frontSize.width, frontSize.height)

        try {
            manager.openCamera(backId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    backDevice = camera
                    configureSession(camera, DualCameraRole.BACK)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    backDevice = null
                    fail("Rear camera disconnected.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    backDevice = null
                    fail("Rear camera error: $error")
                }
            }, h)

            manager.openCamera(frontId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    frontDevice = camera
                    configureSession(camera, DualCameraRole.FRONT)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    frontDevice = null
                    fail("Front camera disconnected.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    frontDevice = null
                    fail("Front camera error: $error")
                }
            }, h)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera failed", e)
            fail(e.message ?: "Could not open the cameras.")
        }
    }

    private var backInputSurface: Surface? = null
    private var frontInputSurface: Surface? = null

    private fun configureSession(camera: CameraDevice, role: DualCameraRole) {
        val h = handler ?: return
        val target = if (role == DualCameraRole.BACK) backInputSurface else frontInputSurface
        if (target == null) {
            fail("Missing camera input surface.")
            return
        }
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
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
                    if (frontConfigured && backConfigured) {
                        compositor?.setRenderingEnabled(true)
                        // Render an initial frame once both textures exist.
                        compositor?.renderFrame()
                        publish(State.PREVIEWING, null)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    fail("Failed to configure the ${role.name.lowercase()} capture session.")
                }
            }, h)
        } catch (e: Exception) {
            fail("Failed to configure the ${role.name.lowercase()} camera.")
        }
    }

    private fun fail(message: String) {
        publish(State.ERROR, message)
        stopInternal()
    }

    private fun publish(state: State, message: String?) {
        Handler(context.mainLooper).post { listener?.onState(state, message) }
    }

    /** Stops and releases everything. Safe to call more than once. */
    fun stop() {
        running = false
        val h = handler
        if (h == null) {
            stopInternal()
        } else {
            h.post { stopInternal() }
        }
    }

    fun release() = stop()

    private fun stopInternal() {
        try {
            frontSession?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            backSession?.stopRepeating()
        } catch (_: Exception) {
        }
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
        frontConfigured = false
        backConfigured = false

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

        try {
            frontInputSurface?.release()
        } catch (_: Exception) {
        }
        try {
            backInputSurface?.release()
        } catch (_: Exception) {
        }
        frontInputSurface = null
        backInputSurface = null

        overlayLayer?.release()
        overlayLayer = null

        compositor?.release()
        compositor = null

        try {
            orientationManager.stop()
        } catch (_: Exception) {
        }

        running = false
        handler = null
        thread?.quitSafely()
        thread = null
    }

    private fun selectSourceSize(sizes: List<Size>): Size {
        if (sizes.isEmpty()) return Size(1280, 720)
        val landscape = sizes.filter { it.width >= it.height }
        val pool = landscape.ifEmpty { sizes }
        // Prefer ~720p, then the closest smaller, otherwise the smallest.
        return pool.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: pool.firstOrNull { it.width <= 1280 && it.width >= 640 }
            ?: pool.minByOrNull { it.width.toLong() * it.height }
            ?: pool.first()
    }

    companion object {
        private const val TAG = "DualCameraController"
    }
}
