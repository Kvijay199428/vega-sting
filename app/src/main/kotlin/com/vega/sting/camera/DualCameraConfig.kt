package com.vega.sting.camera

enum class DualCameraLayout {
    AUTO,
    PORTRAIT_STACK,
    LANDSCAPE_SPLIT
}

enum class DualCameraScalingMode {
    CROP_TO_FILL,
    FIT_WITH_BARS
}

data class DualCameraConfig(
    val layout: DualCameraLayout = DualCameraLayout.AUTO,
    val frontOnTop: Boolean = true,
    val frontOnLeft: Boolean = true,
    val splitRatio: Float = 0.5f,
    val outputQuality: String = "720P",
    val fps: Int = 30,
    val mirrorFront: Boolean = false,
    val scalingMode: DualCameraScalingMode = DualCameraScalingMode.CROP_TO_FILL,
    val enabled: Boolean = false
)
