package com.vega.sting.overlay

enum class OverlayPosition {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT;

    fun displayName(): String {
        return name.replace('_', ' ')
    }

    companion object {
        fun from(value: String): OverlayPosition {
            return entries.firstOrNull { it.name == value } ?: TOP_CENTER
        }
    }
}

data class OverlaySettings(
    val timestampEnabled: Boolean = true,
    val timestampPosition: OverlayPosition = OverlayPosition.TOP_CENTER,
    val timestampFormat: String = "yyyy-MM-dd HH:mm:ss",
    val signature: String = "VEGA STING",
    val signaturePosition: OverlayPosition = OverlayPosition.BOTTOM_LEFT,
    val textSizeSp: Int = 18
)
