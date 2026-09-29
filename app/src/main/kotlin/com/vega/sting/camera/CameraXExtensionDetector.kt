package com.vega.sting.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Uses CameraX Extensions API to detect OEM-exposed processing modes.
 *
 * IMPORTANT: This is used for **detection only**.
 * The actual recording pipeline stays on Camera2 + MediaRecorder.
 * CameraX extensions require CameraX UseCases to apply, which would
 * conflict with the OverlayRenderer GL pipeline.
 */
object CameraXExtensionDetector {

    private const val TAG = "CamXExtDetector"

    /**
     * Detects which CameraX extension modes are available on the back camera.
     * Must be called from a coroutine. Returns an [ExtensionSupport] snapshot.
     */
    suspend fun detect(context: Context): ExtensionSupport {
        return try {
            val cameraProvider = getCameraProvider(context)
            val extensionsManager = getExtensionsManager(context, cameraProvider)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            ExtensionSupport(
                hdr = extensionsManager.isExtensionAvailable(
                    cameraSelector, ExtensionMode.HDR
                ),
                night = extensionsManager.isExtensionAvailable(
                    cameraSelector, ExtensionMode.NIGHT
                ),
                bokeh = extensionsManager.isExtensionAvailable(
                    cameraSelector, ExtensionMode.BOKEH
                ),
                faceRetouch = extensionsManager.isExtensionAvailable(
                    cameraSelector, ExtensionMode.FACE_RETOUCH
                ),
                auto = extensionsManager.isExtensionAvailable(
                    cameraSelector, ExtensionMode.AUTO
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Extension detection failed, returning empty support", e)
            ExtensionSupport()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun getCameraProvider(context: Context): ProcessCameraProvider {
        return suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    continuation.resume(future.get())
                } catch (e: Exception) {
                    continuation.cancel(e)
                }
            }, context.mainExecutor)
        }
    }

    private suspend fun getExtensionsManager(
        context: Context,
        cameraProvider: ProcessCameraProvider
    ): ExtensionsManager {
        return suspendCancellableCoroutine { continuation ->
            val future = ExtensionsManager.getInstanceAsync(context, cameraProvider)
            future.addListener({
                try {
                    continuation.resume(future.get())
                } catch (e: Exception) {
                    continuation.cancel(e)
                }
            }, context.mainExecutor)
        }
    }
}
