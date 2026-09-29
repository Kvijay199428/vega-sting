package com.vega.sting.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class OverlayRenderer(
    private val recorderSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val settings: OverlaySettings,
    private val scaledDensity: Float,
    private val rotationHint: Int,
    private val renderHandler: Handler
) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var cameraTextureId = 0
    private var overlayTextureId = 0
    private var cameraProgram = 0
    private var overlayProgram = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var overlayBitmap: Bitmap? = null
    private var overlayCanvas: Canvas? = null
    private var lastOverlaySecond = -1L
    private var released = false

    // ── Logical display space ─────────────────────────────────────────
    // Overlay text is authored in the *final displayed* orientation (what the
    // player shows after honoring the rotation hint) and pre-counter-rotated
    // into the buffer via the canvas transform. For hint 90/270 the displayed
    // frame is portrait (the buffer is landscape).
    private val baseScale: Float
        get() = (kotlin.math.min(width, height) / 720f).coerceAtLeast(1f)
    private val isPortraitHint: Boolean
        get() = rotationHint % 180 == 90
    private val logicalWidth: Int
        get() = if (isPortraitHint) height else width
    private val logicalHeight: Int
        get() = if (isPortraitHint) width else height

    private val textureMatrix = FloatArray(16)
    private val timestampFormatter = TimestampFormatter(settings.timestampFormat)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = settings.textSizeSp * scaledDensity * baseScale
        typeface = Typeface.MONOSPACE
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    private val signaturePaint = Paint(textPaint).apply {
        color = Color.rgb(255, 136, 0)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(153, 0, 0, 0)
    }
    private val textBounds = RectF()

    fun start(): Surface {
        initEgl()
        cameraTextureId = createExternalTexture()
        overlayTextureId = createTexture()
        cameraProgram = createProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        overlayProgram = createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        overlayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        overlayCanvas = Canvas(overlayBitmap!!)
        surfaceTexture = SurfaceTexture(cameraTextureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ renderFrame() }, renderHandler)
        }
        cameraSurface = Surface(surfaceTexture)
        return cameraSurface!!
    }

    fun release() {
        released = true
        try {
            surfaceTexture?.setOnFrameAvailableListener(null)
        } catch (_: Exception) {
        }
        try {
            cameraSurface?.release()
            surfaceTexture?.release()
            overlayBitmap?.recycle()
        } catch (_: Exception) {
        }
        try {
            if (cameraProgram != 0) GLES20.glDeleteProgram(cameraProgram)
            if (overlayProgram != 0) GLES20.glDeleteProgram(overlayProgram)
            if (cameraTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
            if (overlayTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(overlayTextureId), 0)
        } catch (_: Exception) {
        }
        releaseEgl()
    }

    private fun renderFrame() {
        if (released) return
        try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(textureMatrix)
            updateOverlayIfNeeded()

            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawCamera()
            drawOverlay()
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Maps logical display coordinates into the video buffer so text ends up
     * upright after the player honors the rotation hint. The camera texture is
     * intentionally NOT rotated in GL — the camera image must stay in sensor
     * native orientation for MediaRecorder.
     */
    private fun applyDisplayTransform(canvas: Canvas) {
        when (rotationHint % 360) {
            90 -> {
                canvas.translate(0f, (height - 1).toFloat())
                canvas.rotate(-90f)
            }

            180 -> {
                canvas.translate((width - 1).toFloat(), (height - 1).toFloat())
                canvas.rotate(180f)
            }

            270 -> {
                canvas.translate((width - 1).toFloat(), 0f)
                canvas.rotate(90f)
            }
        }
    }

    private fun updateOverlayIfNeeded() {
        val second = System.currentTimeMillis() / 1000L
        if (second == lastOverlaySecond) return
        lastOverlaySecond = second

        val bitmap = overlayBitmap ?: return
        val canvas = overlayCanvas ?: return
        bitmap.eraseColor(Color.TRANSPARENT)

        canvas.save()
        applyDisplayTransform(canvas)

        if (settings.timestampEnabled) {
            drawOverlayText(
                canvas = canvas,
                text = timestampFormatter.format(second * 1000L),
                paint = textPaint,
                position = settings.timestampPosition
            )
        }

        if (settings.signature.isNotBlank()) {
            drawOverlayText(
                canvas = canvas,
                text = settings.signature,
                paint = signaturePaint,
                position = settings.signaturePosition
            )
        }

        canvas.restore()

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun drawOverlayText(
        canvas: Canvas,
        text: String,
        paint: Paint,
        position: OverlayPosition
    ) {
        val margin = 16f * baseScale
        val point = PositionCalculator.calculate(position, text, paint, logicalWidth, logicalHeight, margin)
        val metrics = paint.fontMetrics
        val paddingX = 8f * baseScale
        val paddingY = 5f * baseScale
        textBounds.set(
            point.x - paddingX,
            point.y + metrics.ascent - paddingY,
            point.x + paint.measureText(text) + paddingX,
            point.y + metrics.descent + paddingY
        )
        canvas.drawRoundRect(textBounds, 4f, 4f, backgroundPaint)
        canvas.drawText(text, point.x, point.y, paint)
    }

    private fun drawCamera() {
        GLES20.glUseProgram(cameraProgram)
        val positionHandle = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord")
        val matrixHandle = GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix")

        VERTEX_BUFFER.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, VERTEX_BUFFER)

        CAMERA_TEX_BUFFER.position(0)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, CAMERA_TEX_BUFFER)

        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
    }

    private fun drawOverlay() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(overlayProgram)
        val positionHandle = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")

        VERTEX_BUFFER.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, VERTEX_BUFFER)

        OVERLAY_TEX_BUFFER.position(0)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, OVERLAY_TEX_BUFFER)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(
            eglDisplay,
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            ),
            0,
            configs,
            0,
            configs.size,
            numConfigs,
            0
        )
        val config = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            recorderSurface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun releaseEgl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (_: Exception) {
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    private fun createTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    private fun createProgram(vertexShader: String, fragmentShader: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertex)
            GLES20.glAttachShader(it, fragment)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }
    }

    companion object {
        private fun floatBuffer(values: FloatArray): FloatBuffer {
            return ByteBuffer
                .allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
        }

        private val VERTEX_BUFFER = floatBuffer(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
            )
        )

        private val CAMERA_TEX_BUFFER = floatBuffer(
            floatArrayOf(
                0f, 1f,
                0f, 0f,
                1f, 1f,
                1f, 0f
            )
        )

        private val OVERLAY_TEX_BUFFER = floatBuffer(
            floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
            )
        )

        private const val CAMERA_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private const val OVERLAY_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val OVERLAY_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
