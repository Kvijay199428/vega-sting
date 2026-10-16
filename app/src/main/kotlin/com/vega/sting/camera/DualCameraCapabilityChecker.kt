package com.vega.sting.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.core.content.ContextCompat

/**
 * Probes whether this device can actually capture the front and rear cameras at
 * the same time, and what it can encode.
 *
 * Presence of two cameras is never treated as proof: on Android 11+ the
 * platform's allowed concurrent-camera sets are consulted, and the encoder is
 * asked directly whether it can handle the composite output. The result is
 * honest about what could not be verified.
 */
class DualCameraCapabilityChecker(private val context: Context) {

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun check(): DualCameraCapabilityResult {
        val permission = hasCameraPermission()
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = manager.cameraIdList
            if (ids.isEmpty()) {
                return unsupported(
                    DualCameraCapabilityStatus.NO_CAMERAS, permission,
                    "This device does not report any cameras."
                )
            }

            var frontId: String? = null
            var backId: String? = null
            for (id in ids) {
                val facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> if (frontId == null) frontId = id
                    CameraCharacteristics.LENS_FACING_BACK -> if (backId == null) backId = id
                }
            }

            if (frontId == null || backId == null) {
                return unsupported(
                    DualCameraCapabilityStatus.MISSING_FACING_CAMERA, permission,
                    if (frontId == null && backId == null) {
                        "No front or rear camera is available."
                    } else if (frontId == null) {
                        "This device has no front camera."
                    } else {
                        "This device has no rear camera."
                    },
                    frontId = frontId, backId = backId
                )
            }

            val frontChars = manager.getCameraCharacteristics(frontId)
            val backChars = manager.getCameraCharacteristics(backId)

            val previewFront = outputSizes(frontChars, SurfaceTexture::class.java)
            val previewBack = outputSizes(backChars, SurfaceTexture::class.java)
            val recordBack = outputSizes(backChars, MediaRecorder::class.java)
            val recordFront = outputSizes(frontChars, MediaRecorder::class.java)

            val maxFpsFront = maxSustainedFps(frontChars)
            val maxFpsBack = maxSustainedFps(backChars)
            val fpsOptions = DualCameraConfig.FPS_OPTIONS.filter { it <= maxFpsFront && it <= maxFpsBack }

            // Concurrent-camera verification.
            var concurrentVerified = false
            var concurrent = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                concurrentVerified = true
                concurrent = try {
                    DualCameraCapabilityResult.concurrentPairAllowed(
                        manager.concurrentCameraIds, frontId, backId
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "concurrentCameraIds unavailable", e)
                    false
                }
            }

            val qualities = feasibleQualities(recordBack, recordFront, fpsOptions)
            val codecs = supportedVideoCodecs(recordBack)

            if (!concurrent) {
                val reason = if (!concurrentVerified) {
                    "Concurrent capture can only be verified on Android 11 (API 30) and newer."
                } else {
                    "This device does not list the front and rear cameras as a concurrent pair."
                }
                return DualCameraCapabilityResult(
                    status = DualCameraCapabilityStatus.CONCURRENT_UNSUPPORTED,
                    frontCameraId = frontId,
                    backCameraId = backId,
                    supportsConcurrentCapture = false,
                    concurrentVerified = concurrentVerified,
                    previewSizesFront = previewFront,
                    previewSizesBack = previewBack,
                    supportedFps = fpsOptions,
                    supportedQualities = qualities,
                    supportedCodecs = codecs,
                    hasCameraPermission = permission,
                    isPreviewSupported = false,
                    isRecordingSupported = false,
                    reason = reason
                )
            }

            val qualitiesForRecord = qualities.ifEmpty { listOf(DualCameraQuality.Q_720P) }
            return DualCameraCapabilityResult(
                status = DualCameraCapabilityStatus.READY,
                frontCameraId = frontId,
                backCameraId = backId,
                supportsConcurrentCapture = true,
                concurrentVerified = true,
                previewSizesFront = previewFront,
                previewSizesBack = previewBack,
                supportedFps = fpsOptions.ifEmpty { listOf(30) },
                supportedQualities = qualitiesForRecord,
                supportedCodecs = codecs.ifEmpty { listOf("video/avc") },
                hasCameraPermission = permission,
                isPreviewSupported = true,
                isRecordingSupported = true,
                reason = if (permission) null else "Camera or microphone permission has not been granted."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Dual-camera capability probe failed", e)
            return unsupported(
                DualCameraCapabilityStatus.ERROR, permission,
                e.message ?: "Capability probe failed."
            )
        }
    }

    private fun unsupported(
        status: DualCameraCapabilityStatus,
        permission: Boolean,
        reason: String,
        frontId: String? = null,
        backId: String? = null
    ) = DualCameraCapabilityResult(
        status = status,
        frontCameraId = frontId,
        backCameraId = backId,
        hasCameraPermission = permission,
        reason = reason
    )

    @Suppress("UNCHECKED_CAST")
    private fun outputSizes(chars: CameraCharacteristics, target: Class<*>): List<Size> {
        return try {
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
            (map.getOutputSizes(target)?.toList() ?: emptyList())
                .sortedByDescending { it.width.toLong() * it.height }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun maxSustainedFps(chars: CameraCharacteristics): Int {
        val ranges: Array<Range<Int>>? = chars.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        )
        if (ranges.isNullOrEmpty()) return 30
        // Prefer a range whose lower bound is a realistic AE ceiling (>=24) so a
        // high-speed 120/240 range does not masquerade as a normal capture rate.
        val normal = ranges.filter { it.lower >= 24 }
        return (normal.ifEmpty { ranges.toList() }).maxOf { it.upper }
    }

    private fun feasibleQualities(
        recordBack: List<Size>,
        recordFront: List<Size>,
        fpsOptions: List<Int>
    ): List<DualCameraQuality> {
        if (recordBack.isEmpty()) return emptyList()
        val maxBack = recordBack.first()
        val maxFront = recordFront.firstOrNull() ?: maxBack
        val result = mutableListOf<DualCameraQuality>()
        for (quality in DualCameraQuality.entries) {
            val needW = quality.longSide
            val needH = quality.shortSide
            val cameraOk = maxBack.width >= needW && maxBack.height >= needH &&
                maxFront.width * maxFront.height >= needH * needH
            val encoderOk = supportedVideoCodecs(listOf(Size(needW, needH))).isNotEmpty()
            val fpsOk = fpsOptions.isNotEmpty()
            if (cameraOk && encoderOk && fpsOk) result += quality
        }
        return result
    }

    /** Video encoder MIME types that can encode the largest of the given sizes. */
    private fun supportedVideoCodecs(sizes: List<Size>): List<String> {
        val target = sizes.firstOrNull() ?: return emptyList()
        return try {
            buildList {
                if (encoderSupports("video/avc", target.width, target.height)) add("video/avc")
                if (encoderSupports("video/hevc", target.width, target.height)) add("video/hevc")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encoderSupports(mime: String, width: Int, height: Int): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            try {
                val caps: MediaCodecInfo.CodecCapabilities = info.getCapabilitiesForType(mime)
                val video = caps.videoCapabilities ?: continue
                if (video.isSizeSupported(width, height)) return true
            } catch (_: Exception) {
                // Ignore a codec that refuses to describe itself.
            }
        }
        return false
    }

    companion object {
        private const val TAG = "DualCapability"
    }
}
