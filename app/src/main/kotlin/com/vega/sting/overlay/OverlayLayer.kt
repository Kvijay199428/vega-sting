package com.vega.sting.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the watermark overlay (timestamp + signature) as a transparent,
 * blended quad on top of whatever is already in the current EGL surface.
 *
 * This is the single overlay implementation: the single-camera [OverlayRenderer]
 * and the dual-camera [com.vega.sting.camera.DualCameraCompositor] both own an
 * instance, so the two pipelines cannot disagree about how the watermark is
 * authored, positioned or scaled.
 *
 * The layer owns no EGL context of its own — the caller must have its GL
 * context current before [ensureInitialized], [updateIfNeeded],
 * [drawOverlayQuad] and [release]. All work happens on that caller's render
 * thread.
 *
 * [rotationHint] is the clockwise rotation the player applies to the buffer:
 * pass the recorder's hint (0/90/180/270) for the sensor-native single-camera
 * buffer, or 0 for the dual-camera composite, which is already upright.
 */
class OverlayLayer(
    private val width: Int,
    private val height: Int,
    private val settings: OverlaySettings,
    private val scaledDensity: Float,
    private val rotationHint: Int
) {
    private var textureId = 0
    private var program = 0
    private var positionHandle = -1
    private var texHandle = -1
    private var overlayBitmap: Bitmap? = null
    private var overlayCanvas: Canvas? = null
    private var lastOverlaySecond = -1L
    private var initialized = false
    private var released = false

    // ── Logical display space ─────────────────────────────────────────
    // Overlay text is authored in the *final displayed* orientation (what the
    // player shows after honoring the rotation hint) and pre-counter-rotated
    // into the buffer via the canvas transform. For hint 90/270 the displayed
    // frame is portrait (the buffer is landscape). For hint 0 the buffer is
    // already the displayed frame (the dual-camera composite).
    private val baseScale = OverlayGeometry.baseScale(width, height)
    private val logicalWidth = OverlayGeometry.logicalWidth(width, height, rotationHint)
    private val logicalHeight = OverlayGeometry.logicalHeight(width, height, rotationHint)

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

    /** Whether the overlay has anything to draw (master switch + content). */
    val hasContent: Boolean
        get() = settings.timestampEnabled || settings.signature.isNotBlank()

    /** Creates the GL objects and the backing bitmap. GL context must be current. */
    fun ensureInitialized() {
        if (initialized || released) return
        textureId = createTexture()
        program = createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        overlayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        overlayCanvas = Canvas(overlayBitmap!!)
        initialized = true
    }

    /**
     * Redraws and re-uploads the overlay bitmap at most once per wall-clock
     * second, so a live timestamp ticks without re-rasterising every frame.
     * GL context must be current.
     */
    fun updateIfNeeded(nowMillis: Long = System.currentTimeMillis()) {
        if (released || !hasContent) return
        ensureInitialized()
        val second = nowMillis / 1000L
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

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * Blends the overlay texture over the current viewport. GL context must be
     * current and the viewport must cover the whole output frame.
     */
    fun drawOverlayQuad() {
        if (released || !hasContent) return
        ensureInitialized()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)

        VERTEX_BUFFER.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, VERTEX_BUFFER)

        OVERLAY_TEX_BUFFER.position(0)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, OVERLAY_TEX_BUFFER)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Frees the bitmap and GL objects. GL context should still be current. */
    fun release() {
        released = true
        try {
            overlayBitmap?.recycle()
        } catch (_: Exception) {
        }
        overlayBitmap = null
        overlayCanvas = null
        try {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        } catch (_: Exception) {
        }
        program = 0
        textureId = 0
        initialized = false
    }

    /**
     * Maps logical display coordinates into the video buffer so text ends up
     * upright after the player honors the rotation hint. The camera texture is
     * intentionally NOT rotated in GL — the camera image must stay in sensor
     * native orientation for MediaRecorder. A hint of 0 (the dual composite)
     * needs no transform: buffer and display space are identical.
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

        private val OVERLAY_TEX_BUFFER = floatBuffer(
            floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
            )
        )

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
