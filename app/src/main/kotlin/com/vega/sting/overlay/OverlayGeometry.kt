package com.vega.sting.overlay

import kotlin.math.min

/**
 * Pure helpers describing the logical (displayed) overlay space relative to the
 * video buffer a frame is rendered into.
 *
 * Kept free of Android/GL types so the single-camera path (sensor-native buffer
 * plus a rotation hint) and the dual-camera path (already-upright composite,
 * hint 0) can be reasoned about and unit-tested together.
 */
object OverlayGeometry {
    private const val BASE_REFERENCE_PX = 720f

    /** Text/edge scale relative to a 720px short edge, never below 1. */
    fun baseScale(width: Int, height: Int): Float =
        (min(width, height) / BASE_REFERENCE_PX).coerceAtLeast(1f)

    /**
     * True when the player rotates the buffer a quarter turn to display it, so
     * the displayed frame is portrait while the buffer is landscape.
     */
    fun isPortraitHint(rotationHint: Int): Boolean = rotationHint % 180 == 90

    /** Width of the frame the overlay is authored against, in display space. */
    fun logicalWidth(width: Int, height: Int, rotationHint: Int): Int =
        if (isPortraitHint(rotationHint)) height else width

    /** Height of the frame the overlay is authored against, in display space. */
    fun logicalHeight(width: Int, height: Int, rotationHint: Int): Int =
        if (isPortraitHint(rotationHint)) width else height
}
