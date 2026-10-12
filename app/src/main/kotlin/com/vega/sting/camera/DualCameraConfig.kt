package com.vega.sting.camera

enum class DualCameraLayout {
    PORTRAIT_STACK,
    LANDSCAPE_SPLIT
}

data class DualCameraConfig(
    val layout: DualCameraLayout = DualCameraLayout.PORTRAIT_STACK,
    val frontOnTop: Boolean = true,
    val frontOnLeft: Boolean = true,
    val splitRatio: Float = 0.5f,
    val outputQuality: String = "720P",
    val mirrorFront: Boolean = true
)
