package com.vega.sting.camera


data class DeviceProfile(
    
    val profileVersion: Int = CURRENT_PROFILE_VERSION,

    
    val fingerprint: String = "",

    
    val device: String = "",

    
    val manufacturer: String = "",

    
    val sdkVersion: Int = 0,

    
    val scannedAt: Long = 0L,

    
    val cameras: Map<String, CameraCapabilities> = emptyMap(),

    
    val encoders: EncoderCapabilities = EncoderCapabilities(),

    
    val extensions: ExtensionSupport = ExtensionSupport(),

    
    val vendorTags: List<String> = emptyList()
) {
    companion object {
        const val CURRENT_PROFILE_VERSION = 1
    }
}


fun resolveEffectiveVideoCodec(
    requestedCodec: String,
    videoEncoders: List<VideoEncoderInfo>
): String {
    if (requestedCodec != "H.265") return "H264"
    val hasHardwareHevc = videoEncoders.any { encoder ->
        encoder.mimeType == "video/hevc" &&
            encoder.isHardwareAccelerated &&
            encoder.maxWidth >= 1920 &&
            encoder.maxHeight >= 1080
    }
    return if (hasHardwareHevc) "HEVC" else "H264"
}


data class CameraCapabilities(
    val cameraId: String = "",

    
    val lensFacing: Int = -1,

    
    val hardwareLevel: String = "UNKNOWN",

    
    val sensorOrientation: Int = 0,

    
    val videoSizes: List<String> = emptyList(),

    
    val fpsRanges: List<String> = emptyList(),

    
    val stabilization: StabilizationProfile = StabilizationProfile(),

    
    val sceneModes: List<String> = emptyList(),

    
    val awbModes: List<String> = emptyList(),

    
    val afModes: List<String> = emptyList(),

    
    val aeModes: List<String> = emptyList(),

    
    val noiseReductionModes: List<String> = emptyList(),

    
    val edgeModes: List<String> = emptyList(),

    
    val capabilities: List<String> = emptyList(),

    
    val dynamicRangeProfiles: List<String> = emptyList()
)

data class StabilizationProfile(
    
    val videoStabilization: Boolean = false,

    
    val opticalStabilization: Boolean = false
)


data class EncoderCapabilities(
    val videoEncoders: List<VideoEncoderInfo> = emptyList(),
    val audioEncoders: List<AudioEncoderInfo> = emptyList()
)

data class VideoEncoderInfo(
    val name: String = "",
    val mimeType: String = "",

    
    val profiles: List<String> = emptyList(),

    
    val maxWidth: Int = 0,

    
    val maxHeight: Int = 0,

    
    val bitrateRange: String = "",

    
    val frameRateRange: String = "",

    
    val isHardwareAccelerated: Boolean = false,

    
    val colorFormats: List<Int> = emptyList()
)

data class AudioEncoderInfo(
    val name: String = "",
    val mimeType: String = "",
    val maxChannels: Int = 0,
    val bitrateRange: String = "",
    val sampleRateRanges: List<String> = emptyList()
)


data class ExtensionSupport(
    val hdr: Boolean = false,
    val night: Boolean = false,
    val bokeh: Boolean = false,
    val faceRetouch: Boolean = false,
    val auto: Boolean = false
)
