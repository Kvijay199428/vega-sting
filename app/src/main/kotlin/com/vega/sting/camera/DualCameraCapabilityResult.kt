package com.vega.sting.camera

import android.util.Size

/** Why the dual-camera feature can or cannot run on this device. */
enum class DualCameraCapabilityStatus {
    /** The device exposes fewer than two usable cameras. */
    NO_CAMERAS,

    /** A front or rear camera is missing. */
    MISSING_FACING_CAMERA,

    /** Both cameras exist but the platform does not allow them concurrently. */
    CONCURRENT_UNSUPPORTED,

    /** Enumeration passed; runtime startup still has to be validated. */
    READY,

    /** Enumeration itself failed. */
    ERROR
}

/**
 * The outcome of a real capability probe. This is the single object the UI and
 * the recording service consult; nothing decides to start dual capture from the
 * mere presence of two cameras.
 */
data class DualCameraCapabilityResult(
    val status: DualCameraCapabilityStatus,
    val frontCameraId: String? = null,
    val backCameraId: String? = null,
    val supportsConcurrentCapture: Boolean = false,
    /** True only when the platform actually reported concurrent-camera sets. */
    val concurrentVerified: Boolean = false,
    val previewSizesFront: List<Size> = emptyList(),
    val previewSizesBack: List<Size> = emptyList(),
    val supportedFps: List<Int> = emptyList(),
    val supportedQualities: List<DualCameraQuality> = emptyList(),
    val supportedCodecs: List<String> = emptyList(),
    val hasCameraPermission: Boolean = false,
    val isPreviewSupported: Boolean = false,
    val isRecordingSupported: Boolean = false,
    val reason: String? = null
) {
    val isReady: Boolean get() = status == DualCameraCapabilityStatus.READY

    /** True when the feature can be offered at all (before runtime startup). */
    val canOfferDualCamera: Boolean
        get() = isReady && hasCameraPermission && isRecordingSupported

    companion object {
        /**
         * Order-independent membership test against the platform's allowed
         * concurrent camera sets. A set must contain both cameras.
         */
        fun concurrentPairAllowed(
            concurrentSets: Collection<Set<String>>,
            first: String,
            second: String
        ): Boolean = concurrentSets.any { set ->
            set.size >= 2 && set.contains(first) && set.contains(second)
        }
    }
}
