package com.vega.sting.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

class DualCameraCapabilityChecker(private val context: Context) {
    fun check(): DualCameraCapabilityResult {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = manager.cameraIdList
            var frontId: String? = null
            var backId: String? = null
            for (id in ids) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> frontId = id
                    CameraCharacteristics.LENS_FACING_BACK -> backId = id
                }
            }
            if (frontId == null || backId == null) {
                return DualCameraCapabilityResult(
                    hasFrontCamera = frontId != null,
                    hasBackCamera = backId != null,
                    reason = "Missing front or back camera"
                )
            }

            var supportsConcurrent = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val concurrent = manager.concurrentCameraIds
                    supportsConcurrent = concurrent.contains(setOf(frontId, backId))
                } catch (e: Exception) {
                    supportsConcurrent = false
                }
            }
            return DualCameraCapabilityResult(
                hasFrontCamera = true,
                hasBackCamera = true,
                supportsConcurrentCapture = supportsConcurrent,
                isPreviewSupported = true,
                isRecordingSupported = supportsConcurrent,
                reason = if (supportsConcurrent) null else "Concurrent capture not verified on this API level"
            )
        } catch (e: Exception) {
            return DualCameraCapabilityResult(reason = e.message)
        }
    }
}
