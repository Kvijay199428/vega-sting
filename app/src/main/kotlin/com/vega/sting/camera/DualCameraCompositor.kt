package com.vega.sting.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Which physical camera a compositor input belongs to. */
enum class DualCameraRole { FRONT, BACK }

/**
 * Composites the two live camera streams into a single output surface.
 *
 * Two `GL_TEXTURE_EXTERNAL_OES` textures are each backed by a camera
 * [SurfaceTexture]. When both cameras have delivered at least one frame the
 * compositor draws a single composite frame using the shared
 * [DualCameraGeometry] maths, so the preview and the recorded file can never
 * disagree about layout, placement, split ratio, scaling or mirroring.
 *
 * All GL work happens on the [renderHandler] thread; callers must already be on
 * that thread (the controller runs camera callbacks and GL on the same looper).
 */
class DualCameraCompositor(private val renderHandler: Handler) {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var positionHandle = 0
    private var texHandle = 0
    private var matrixHandle = 0
    private var samplerHandle = 0

    private var outputSurface: Surface? = null
    private var outputWidth = 0
    private var outputHeight = 0

    private class Input(
        val role: DualCameraRole,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val textureId: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface
    ) {
        var hasFrame = false
    }

    private var front: Input? = null
    private var back: Input? = null
    private var initialized = false
    private var renderingEnabled = true
    private var loggedNoSurface = false

    private var config = DualCameraConfig.DEFAULTS
    private var portraitOutput = false

    private val texMatrixFront = FloatArray(16)
    private val texMatrixBack = FloatArray(16)

    private val posBuffer = nativeFloatBuffer(8)
    private val texBuffer = nativeFloatBuffer(8)

    fun isInitialized(): Boolean = initialized

    /**
     * Creates the EGL context and the output surface binding. Safe to call
     * again to move the output to a new surface (e.g. preview re-attached).
     */
    fun initialize(surface: Surface, width: Int, height: Int): Boolean {
        return try {
            if (!initialized) {
                initEgl()
                // The EGL context is only current once an output surface is
                // bound, so bind first: creating the shader program before this
                // point would run every GL call with no current context and
                // silently yield an unusable program (black frames).
                bindOutput(surface, width, height)
                program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                if (program == 0) {
                    throw IllegalStateException("Compositor shader program failed to build")
                }
                positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
                texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
                matrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
                samplerHandle = GLES20.glGetUniformLocation(program, "sTexture")
                if (positionHandle < 0 || texHandle < 0) {
                    throw IllegalStateException("Compositor shader attributes missing")
                }
                initialized = true
            } else {
                bindOutput(surface, width, height)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Compositor init failed", e)
            initialized = false
            false
        }
    }

    /** Re-points the compositor at a different output surface (same GL context). */
    fun bindOutput(surface: Surface, width: Int, height: Int) {
        outputSurface = surface
        outputWidth = width.coerceAtLeast(1)
        outputHeight = height.coerceAtLeast(1)
        destroyWindowSurface()
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            eglConfig,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            eglSurface = EGL14.EGL_NO_SURFACE
            Log.e(TAG, "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            return
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            return
        }
        loggedNoSurface = false
    }

    /**
     * Creates (or reuses) the camera-facing input surface for [role]. Must be
     * called on the render thread before the camera session that targets it.
     */
    fun createInputSurface(role: DualCameraRole, sourceWidth: Int, sourceHeight: Int): Surface {
        val existing = inputFor(role)
        if (existing != null &&
            existing.sourceWidth == sourceWidth &&
            existing.sourceHeight == sourceHeight
        ) {
            return existing.surface
        }
        // Drop any previous surface for this role before creating a new one.
        existing?.let { releaseInput(it) }

        val textureId = createExternalTexture()
        val surfaceTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(sourceWidth.coerceAtLeast(1), sourceHeight.coerceAtLeast(1))
            setOnFrameAvailableListener({ onFrameAvailable(role) }, renderHandler)
        }
        val input = Input(
            role = role,
            sourceWidth = sourceWidth.coerceAtLeast(1),
            sourceHeight = sourceHeight.coerceAtLeast(1),
            textureId = textureId,
            surfaceTexture = surfaceTexture,
            surface = Surface(surfaceTexture)
        )
        if (role == DualCameraRole.FRONT) front = input else back = input
        return input.surface
    }

    fun setConfig(config: DualCameraConfig, portraitOutput: Boolean) {
        this.config = config
        this.portraitOutput = portraitOutput
    }

    fun setRenderingEnabled(enabled: Boolean) {
        renderingEnabled = enabled
    }

    private fun onFrameAvailable(role: DualCameraRole) {
        val input = inputFor(role) ?: return
        input.hasFrame = true
        val other = if (role == DualCameraRole.FRONT) back else front
        if (!renderingEnabled) return
        if (other == null || !other.hasFrame) return
        renderFrame()
    }

    /** Draws one composite frame. Call on the render thread. */
    fun renderFrame() {
        if (!initialized || !renderingEnabled) return
        val frontInput = front ?: return
        val backInput = back ?: return
        // Never touch the textures until both cameras have delivered a frame;
        // updateTexImage before that would sample undefined content.
        if (!frontInput.hasFrame || !backInput.hasFrame) return
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            if (!loggedNoSurface) {
                loggedNoSurface = true
                Log.e(TAG, "renderFrame skipped: no EGL output surface bound")
            }
            return
        }
        try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            frontInput.surfaceTexture.updateTexImage()
            frontInput.surfaceTexture.getTransformMatrix(texMatrixFront)
            backInput.surfaceTexture.updateTexImage()
            backInput.surfaceTexture.getTransformMatrix(texMatrixBack)

            val frame = DualCameraGeometry.frame(
                config = config,
                width = outputWidth,
                height = outputHeight,
                portraitOutput = portraitOutput,
                frontSourceWidth = frontInput.sourceWidth,
                frontSourceHeight = frontInput.sourceHeight,
                backSourceWidth = backInput.sourceWidth,
                backSourceHeight = backInput.sourceHeight
            )

            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Clip each feed to its own region so a cropped (scaled) feed can
            // never bleed into the other region.
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)

            drawFeed(frame.front, frontInput.textureId, texMatrixFront)
            drawFeed(frame.back, backInput.textureId, texMatrixBack)

            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Exception) {
            Log.e(TAG, "Composite render failed", e)
        }
    }

    /**
     * Draws one feed into its region.
     *
     * `glViewport`/`glScissor` map the normalised device coordinates onto the
     * region, and the quad half-extents ([FeedPlacement.scaleX]/[scaleY])
     * implement crop/letterbox without ever stretching the image. The full-quad
     * texture-coordinate pairing is identical to the app's proven single-camera
     * overlay renderer, so orientation matches the recorded output exactly.
     */
    private fun drawFeed(placement: FeedPlacement, textureId: Int, texMatrix: FloatArray) {
        val r = placement.region
        // glScissor/glViewport use a bottom-left origin.
        val glY = outputHeight - r.bottom
        GLES20.glScissor(r.left, glY, r.width.coerceAtLeast(1), r.height.coerceAtLeast(1))
        GLES20.glViewport(r.left, glY, r.width.coerceAtLeast(1), r.height.coerceAtLeast(1))

        val sx = placement.scaleX
        val sy = placement.scaleY
        // Negating the x extent around the centre mirrors the feed horizontally
        // (the quad is symmetric, so this flips the sampled texture, not the
        // geometry).
        val flip = if (placement.mirrored) -1f else 1f

        // Triangle-strip order: BL, BR, TL, TR.
        posBuffer.position(0)
        posBuffer.put(-sx * flip).put(-sy)
        posBuffer.put(sx * flip).put(-sy)
        posBuffer.put(-sx * flip).put(sy)
        posBuffer.put(sx * flip).put(sy)
        posBuffer.position(0)

        // Proven camera pairing for the full quad: BL(0,1) BR(0,0) TL(1,1) TR(1,0).
        texBuffer.position(0)
        texBuffer.put(0f).put(1f)
        texBuffer.put(0f).put(0f)
        texBuffer.put(1f).put(1f)
        texBuffer.put(1f).put(0f)
        texBuffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, posBuffer)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
    }

    /** Resets per-session frame state (used between recording segments). */
    fun resetFrameState() {
        front?.hasFrame = false
        back?.hasFrame = false
    }

    /** Releases both camera input surfaces/textures (keeps the EGL context). */
    fun releaseInputs() {
        try {
            front?.let { releaseInput(it) }
            back?.let { releaseInput(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Input release warning", e)
        }
        front = null
        back = null
    }

    fun release() {
        releaseInputs()
        try {
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Compositor GL release warning", e)
        }
        releaseEgl()
        initialized = false
    }

    private fun releaseInput(input: Input) {
        try {
            input.surfaceTexture.setOnFrameAvailableListener(null)
            input.surface.release()
            input.surfaceTexture.release()
            GLES20.glDeleteTextures(1, intArrayOf(input.textureId), 0)
        } catch (e: Exception) {
            Log.w(TAG, "Input surface release warning", e)
        }
    }

    private fun inputFor(role: DualCameraRole): Input? =
        if (role == DualCameraRole.FRONT) front else back

    // ---- EGL helpers -------------------------------------------------------

    private var eglConfig: EGLConfig? = null

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize failed")
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] == 0
        ) {
            throw IllegalStateException("eglChooseConfig failed")
        }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
    }

    private fun destroyWindowSurface() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            try {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            } catch (_: Exception) {
            }
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun releaseEgl() {
        destroyWindowSurface()
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (_: Exception) {
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        outputSurface = null
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

    private fun createProgram(vertexShader: String, fragmentShader: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        if (vertex == 0) return 0
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        if (fragment == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "DualCompositor"

        private fun nativeFloatBuffer(size: Int): FloatBuffer =
            ByteBuffer.allocateDirect(size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
