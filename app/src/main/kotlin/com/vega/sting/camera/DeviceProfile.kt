package com.vega.sting.camera

/**
 * Complete device camera profile.
 * Serialized to JSON and cached per-device.
 * Static data — does not change between sessions.
 */
data class DeviceProfile(
    /** Schema version for forward-compatible migration. */
    val profileVersion: Int = CURRENT_PROFILE_VERSION,

    /** Full Build.FINGERPRINT for OTA-change detection. */
    val fingerprint: String = "",

    /** Human-readable device model (Build.MODEL). */
    val device: String = "",

    /** Manufacturer (Build.MANUFACTURER). */
    val manufacturer: String = "",

    /** Android SDK version at scan time. */
    val sdkVersion: Int = 0,

    /** Timestamp of when this profile was generated. */
    val scannedAt: Long = 0L,

    /** Per-camera capability maps keyed by cameraId ("0", "1", …). */
    val cameras: Map<String, CameraCapabilities> = emptyMap(),

    /** Encoder capabilities discovered via MediaCodecList. */
    val encoders: EncoderCapabilities = EncoderCapabilities(),

    /** CameraX extension modes available on this device. */
    val extensions: ExtensionSupport = ExtensionSupport(),

    /** Vendor tags discovered (keys only — values are runtime). */
    val vendorTags: List<String> = emptyList()
) {
    companion object {
        const val CURRENT_PROFILE_VERSION = 1
    }
}

/**
 * Capabilities of a single physical camera (front / back / …).
 */
data class CameraCapabilities(
    val cameraId: String = "",

    /** CameraCharacteristics.LENS_FACING value. */
    val lensFacing: Int = -1,

    /** INFO_SUPPORTED_HARDWARE_LEVEL as readable string. */
    val hardwareLevel: String = "UNKNOWN",

    /** SENSOR_ORIENTATION in degrees. */
    val sensorOrientation: Int = 0,

    /** Supported video output sizes (w×h). */
    val videoSizes: List<String> = emptyList(),

    /** Supported FPS ranges as "min-max". */
    val fpsRanges: List<String> = emptyList(),

    /** Stabilization support. */
    val stabilization: StabilizationProfile = StabilizationProfile(),

    /** CONTROL_AVAILABLE_SCENE_MODES as readable names. */
    val sceneModes: List<String> = emptyList(),

    /** CONTROL_AWB_AVAILABLE_MODES as readable names. */
    val awbModes: List<String> = emptyList(),

    /** CONTROL_AF_AVAILABLE_MODES as readable names. */
    val afModes: List<String> = emptyList(),

    /** CONTROL_AE_AVAILABLE_MODES as readable names. */
    val aeModes: List<String> = emptyList(),

    /** NOISE_REDUCTION_AVAILABLE_MODES as readable names. */
    val noiseReductionModes: List<String> = emptyList(),

    /** EDGE_MODE available modes as readable names. */
    val edgeModes: List<String> = emptyList(),

    /** REQUEST_AVAILABLE_CAPABILITIES as readable names. */
    val capabilities: List<String> = emptyList(),

    /** Dynamic range profiles available (Android 13+). */
    val dynamicRangeProfiles: List<String> = emptyList()
)

data class StabilizationProfile(
    /** Electronic (video) stabilization available. */
    val videoStabilization: Boolean = false,

    /** Optical Image Stabilization available. */
    val opticalStabilization: Boolean = false
)

/**
 * Encoder capabilities discovered from MediaCodecList.
 */
data class EncoderCapabilities(
    val videoEncoders: List<VideoEncoderInfo> = emptyList(),
    val audioEncoders: List<AudioEncoderInfo> = emptyList()
)

data class VideoEncoderInfo(
    val name: String = "",
    val mimeType: String = "",

    /** Supported AVC/HEVC profiles as readable names. */
    val profiles: List<String> = emptyList(),

    /** Max supported width. */
    val maxWidth: Int = 0,

    /** Max supported height. */
    val maxHeight: Int = 0,

    /** Bitrate range "min-max" bps. */
    val bitrateRange: String = "",

    /** Supported frame rate range "min-max". */
    val frameRateRange: String = "",

    /** Whether this encoder is hardware-accelerated. */
    val isHardwareAccelerated: Boolean = false,

    /** Supported color formats. */
    val colorFormats: List<Int> = emptyList()
)

data class AudioEncoderInfo(
    val name: String = "",
    val mimeType: String = "",
    val maxChannels: Int = 0,
    val bitrateRange: String = "",
    val sampleRateRanges: List<String> = emptyList()
)

/**
 * CameraX Extension support (detect-only, not used for recording).
 */
data class ExtensionSupport(
    val hdr: Boolean = false,
    val night: Boolean = false,
    val bokeh: Boolean = false,
    val faceRetouch: Boolean = false,
    val auto: Boolean = false
)
