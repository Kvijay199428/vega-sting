package com.vega.sting.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Range

/**
 * Scans Camera2 hardware capabilities for every physical camera on the device.
 * Produces a map of cameraId → [CameraCapabilities].
 *
 * This is a pure-read operation: it never opens the camera device.
 */
object CameraCapabilityScanner {

    private const val TAG = "CamCapScanner"

    /**
     * Scans all cameras visible to the app and returns their capabilities.
     */
    fun scan(context: Context): Map<String, CameraCapabilities> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val result = mutableMapOf<String, CameraCapabilities>()

        for (cameraId in cameraManager.cameraIdList) {
            try {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                result[cameraId] = extractCapabilities(cameraId, chars)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scan camera $cameraId", e)
            }
        }
        return result
    }

    /**
     * Extracts all vendor tag key names discovered via CameraCharacteristics.keys.
     * Only stores the key names — values are runtime-dependent.
     */
    fun scanVendorTags(context: Context): List<String> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val vendorTags = mutableSetOf<String>()

        for (cameraId in cameraManager.cameraIdList) {
            try {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                for (key in chars.keys) {
                    val name = key.name
                    // Vendor tags typically have a dot-separated namespace
                    // Standard Android keys start with "android."
                    if (!name.startsWith("android.")) {
                        vendorTags.add(name)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read vendor tags for camera $cameraId", e)
            }
        }
        return vendorTags.sorted()
    }

    // ── private ──────────────────────────────────────────────────────────

    private fun extractCapabilities(
        cameraId: String,
        chars: CameraCharacteristics
    ): CameraCapabilities {
        return CameraCapabilities(
            cameraId = cameraId,
            lensFacing = chars.get(CameraCharacteristics.LENS_FACING) ?: -1,
            hardwareLevel = mapHardwareLevel(
                chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ),
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            videoSizes = extractVideoSizes(chars),
            fpsRanges = extractFpsRanges(chars),
            stabilization = extractStabilization(chars),
            sceneModes = extractSceneModes(chars),
            awbModes = extractAwbModes(chars),
            afModes = extractAfModes(chars),
            aeModes = extractAeModes(chars),
            noiseReductionModes = extractNoiseReductionModes(chars),
            edgeModes = extractEdgeModes(chars),
            capabilities = extractRequestCapabilities(chars),
            dynamicRangeProfiles = extractDynamicRangeProfiles(chars)
        )
    }

    // ── Video sizes ─────────────────────────────────────────────────────

    private fun extractVideoSizes(chars: CameraCharacteristics): List<String> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        return map.getOutputSizes(MediaRecorder::class.java)
            ?.map { "${it.width}x${it.height}" }
            ?.sortedByDescending { sizeArea(it) }
            .orEmpty()
    }

    private fun sizeArea(size: String): Int {
        val parts = size.split("x")
        return if (parts.size == 2) parts[0].toInt() * parts[1].toInt() else 0
    }

    // ── FPS ranges ──────────────────────────────────────────────────────

    private fun extractFpsRanges(chars: CameraCharacteristics): List<String> {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return emptyList()
        return ranges.map { range: Range<Int> -> "${range.lower}-${range.upper}" }
            .distinct()
            .sortedByDescending { it.split("-").lastOrNull()?.toIntOrNull() ?: 0 }
    }

    // ── Stabilization ───────────────────────────────────────────────────

    private fun extractStabilization(chars: CameraCharacteristics): StabilizationProfile {
        val videoStab = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val opticalStab = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)

        return StabilizationProfile(
            videoStabilization = videoStab?.contains(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            ) == true,
            opticalStabilization = opticalStab?.contains(
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            ) == true
        )
    }

    // ── Scene modes ─────────────────────────────────────────────────────

    private fun extractSceneModes(chars: CameraCharacteristics): List<String> {
        val modes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
            ?: return emptyList()
        return modes.map { mapSceneMode(it) }.filter { it != "DISABLED" }
    }

    private fun mapSceneMode(mode: Int): String = when (mode) {
        CameraMetadata.CONTROL_SCENE_MODE_DISABLED -> "DISABLED"
        CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY -> "FACE_PRIORITY"
        CameraMetadata.CONTROL_SCENE_MODE_ACTION -> "ACTION"
        CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT -> "PORTRAIT"
        CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE -> "LANDSCAPE"
        CameraMetadata.CONTROL_SCENE_MODE_NIGHT -> "NIGHT"
        CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT -> "NIGHT_PORTRAIT"
        CameraMetadata.CONTROL_SCENE_MODE_THEATRE -> "THEATRE"
        CameraMetadata.CONTROL_SCENE_MODE_BEACH -> "BEACH"
        CameraMetadata.CONTROL_SCENE_MODE_SNOW -> "SNOW"
        CameraMetadata.CONTROL_SCENE_MODE_SUNSET -> "SUNSET"
        CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO -> "STEADYPHOTO"
        CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS -> "FIREWORKS"
        CameraMetadata.CONTROL_SCENE_MODE_SPORTS -> "SPORTS"
        CameraMetadata.CONTROL_SCENE_MODE_PARTY -> "PARTY"
        CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT -> "CANDLELIGHT"
        CameraMetadata.CONTROL_SCENE_MODE_BARCODE -> "BARCODE"
        CameraMetadata.CONTROL_SCENE_MODE_HDR -> "HDR"
        else -> "UNKNOWN($mode)"
    }

    // ── AWB modes ───────────────────────────────────────────────────────

    private fun extractAwbModes(chars: CameraCharacteristics): List<String> {
        val modes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?: return emptyList()
        return modes.map { mapAwbMode(it) }
    }

    private fun mapAwbMode(mode: Int): String = when (mode) {
        CameraMetadata.CONTROL_AWB_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY_DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "SHADE"
        else -> "UNKNOWN($mode)"
    }

    // ── AF modes ────────────────────────────────────────────────────────

    private fun extractAfModes(chars: CameraCharacteristics): List<String> {
        val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: return emptyList()
        return modes.map { mapAfMode(it) }
    }

    private fun mapAfMode(mode: Int): String = when (mode) {
        CameraMetadata.CONTROL_AF_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "UNKNOWN($mode)"
    }

    // ── AE modes ────────────────────────────────────────────────────────

    private fun extractAeModes(chars: CameraCharacteristics): List<String> {
        val modes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            ?: return emptyList()
        return modes.map { mapAeMode(it) }
    }

    private fun mapAeMode(mode: Int): String = when (mode) {
        CameraMetadata.CONTROL_AE_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AE_MODE_ON -> "ON"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> "ON_AUTO_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ON_ALWAYS_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "ON_AUTO_FLASH_REDEYE"
        CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> "ON_EXTERNAL_FLASH"
        else -> "UNKNOWN($mode)"
    }

    // ── Noise reduction ─────────────────────────────────────────────────

    private fun extractNoiseReductionModes(chars: CameraCharacteristics): List<String> {
        val modes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?: return emptyList()
        return modes.map { mapNoiseReduction(it) }
    }

    private fun mapNoiseReduction(mode: Int): String = when (mode) {
        CameraMetadata.NOISE_REDUCTION_MODE_OFF -> "OFF"
        CameraMetadata.NOISE_REDUCTION_MODE_FAST -> "FAST"
        CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL -> "MINIMAL"
        CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "ZERO_SHUTTER_LAG"
        else -> "UNKNOWN($mode)"
    }

    // ── Edge modes ──────────────────────────────────────────────────────

    private fun extractEdgeModes(chars: CameraCharacteristics): List<String> {
        val modes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            ?: return emptyList()
        return modes.map { mapEdgeMode(it) }
    }

    private fun mapEdgeMode(mode: Int): String = when (mode) {
        CameraMetadata.EDGE_MODE_OFF -> "OFF"
        CameraMetadata.EDGE_MODE_FAST -> "FAST"
        CameraMetadata.EDGE_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG -> "ZERO_SHUTTER_LAG"
        else -> "UNKNOWN($mode)"
    }

    // ── Request capabilities ────────────────────────────────────────────

    private fun extractRequestCapabilities(chars: CameraCharacteristics): List<String> {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return emptyList()
        return caps.map { mapCapability(it) }
    }

    private fun mapCapability(cap: Int): String = when (cap) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        else -> "UNKNOWN($cap)"
    }

    // ── Dynamic range profiles ──────────────────────────────────────────

    private fun extractDynamicRangeProfiles(chars: CameraCharacteristics): List<String> {
        if (Build.VERSION.SDK_INT < 33) return emptyList()
        return try {
            val profiles = chars.get(
                CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
            )
            profiles?.supportedProfiles?.map { it.toString() } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Hardware level ──────────────────────────────────────────────────

    private fun mapHardwareLevel(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }
}
