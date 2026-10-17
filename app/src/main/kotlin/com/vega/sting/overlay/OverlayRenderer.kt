package com.vega.sting.overlay

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Single-camera recorder bridge: the camera writes into an external-OES texture
 * this class owns, and each frame is drawn 1:1 into the MediaRecorder surface
 * with the watermark composited on top by a shared [OverlayLayer].
 *
 * The camera image is deliberately NOT rotated in GL; the recorder's
 * `setOrientationHint` rotates the file, and [OverlayLayer] counter-rotates the
 * overlay so it still reads upright after that rotation.
 */
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
    private var cameraProgram = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var released = false

    // The overlay half of the pipeline is shared with the dual-camera compositor.
    private val overlayLayer = OverlayLayer(
        width = width,
        height = height,
        settings = settings,
        scaledDensity = scaledDensity,
        rotationHint = rotationHint
    )

    private val textureMatrix = FloatArray(16)

    fun start(): Surface {
        initEgl()
        cameraTextureId = createExternalTexture()
        cameraProgram = createProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        overlayLayer.ensureInitialized()
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
        } catch (_: Exception) {
        }
        try {
            if (cameraProgram != 0) GLES20.glDeleteProgram(cameraProgram)
            if (cameraTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
        } catch (_: Exception) {
        }
        overlayLayer.release()
        releaseEgl()
    }

    private fun renderFrame() {
        if (released) return
        try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(textureMatrix)
            overlayLayer.updateIfNeeded()

            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawCamera()
            overlayLayer.drawOverlayQuad()
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
    }
}
