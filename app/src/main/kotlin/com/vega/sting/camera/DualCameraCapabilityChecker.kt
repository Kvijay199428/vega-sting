package com.vega.sting.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

class DualCameraCapabilityChecker(private val context: Context) {
    data class Result(
        val supported: Boolean,
        val frontCameraId: String? = null,
        val backCameraId: String? = null,
        val reason: String? = null
    )

    fun check(): Result {
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
                return Result(supported = false, reason = "Missing front or back camera")
            }
            return Result(supported = true, frontCameraId = frontId, backCameraId = backId)
        } catch (e: Exception) {
            return Result(supported = false, reason = e.message)
        }
    }
}
