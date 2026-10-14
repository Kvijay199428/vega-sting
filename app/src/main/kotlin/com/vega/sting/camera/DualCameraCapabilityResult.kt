package com.vega.sting.camera

data class DualCameraCapabilityResult(
    val hasFrontCamera: Boolean = false,
    val hasBackCamera: Boolean = false,
    val supportsConcurrentCapture: Boolean = false,
    val supportedOutputSizes: List<String> = emptyList(),
    val supportedFps: List<Int> = emptyList(),
    val supportedCodecs: List<String> = emptyList(),
    val maxRecommendedQuality: String? = null,
    val isPreviewSupported: Boolean = false,
    val isRecordingSupported: Boolean = false,
    val reason: String? = null
)
