package com.vega.sting.camera

/**
 * A rectangle in output pixels.
 *
 * Right/bottom are exclusive, so `width() == right - left`. Kept free of
 * Android types so the layout maths can be unit tested on the JVM and shared
 * verbatim between the live preview and the recorded output.
 */
data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Where a single camera feed is drawn.
 *
 * [region] is the destination rectangle in the composite; the feed is rendered
 * with `glViewport`/`glScissor` set to that region. [scaleX]/[scaleY] are the
 * half-extents (in NDC) of the drawn quad: 1.0 exactly fills the region on that
 * axis, >1 overflows (cropped), <1 leaves bars. [mirrored] flips the source
 * horizontally. Aspect ratio is always preserved.
 */
data class FeedPlacement(
    val region: PixelRect,
    val scaleX: Float,
    val scaleY: Float,
    val mirrored: Boolean,
    /**
     * Clockwise degrees applied to the feed so it is upright in the composite
     * canvas: 0, 90, 180 or 270. Independent of [mirrored].
     */
    val rotationDegrees: Int = 0
)

/** A fully resolved composite description for one frame. */
data class DualCameraFrame(
    val layout: DualCameraLayout,
    val outputWidth: Int,
    val outputHeight: Int,
    val front: FeedPlacement,
    val back: FeedPlacement
)

/**
 * Pure geometry for the dual-camera composite. Every consumer (live preview,
 * MediaRecorder pipeline) derives its layout from here, so the preview cannot
 * disagree with the recorded file.
 */
object DualCameraGeometry {

    /** Resolves [DualCameraLayout.AUTO] against the effective output orientation. */
    fun resolveLayout(config: DualCameraConfig, portraitOutput: Boolean): DualCameraLayout =
        config.layout.resolve(portraitOutput)

    /** Effective output orientation for a given dimension pair. */
    fun isPortraitOutput(width: Int, height: Int): Boolean = height >= width

    /**
     * Splits the composite into the first (top/left) and second (bottom/right)
     * spatial regions. The split ratio is always the *first* region's fraction.
     * The two regions share an edge exactly, so no gap or overlap can appear.
     */
    fun splitRegions(
        width: Int,
        height: Int,
        portraitOutput: Boolean,
        firstFraction: Float
    ): Pair<PixelRect, PixelRect> {
        val r = firstFraction.coerceIn(0.05f, 0.95f)
        return if (portraitOutput) {
            val split = Math.round(height * r).coerceIn(1, height - 1)
            PixelRect(0, 0, width, split) to PixelRect(0, split, width, height)
        } else {
            val split = Math.round(width * r).coerceIn(1, width - 1)
            PixelRect(0, 0, split, height) to PixelRect(split, 0, width, height)
        }
    }

    /** The region each camera is drawn into, honouring the placement settings. */
    fun cameraRegions(
        config: DualCameraConfig,
        width: Int,
        height: Int,
        portraitOutput: Boolean
    ): Pair<PixelRect, PixelRect> {
        val (first, second) = splitRegions(width, height, portraitOutput, config.splitFraction)
        val frontFirst = if (portraitOutput) config.frontOnTop else config.frontOnLeft
        return if (frontFirst) first to second else second to first
    }

    /**
     * Computes where a feed is drawn inside its region.
     *
     * [sourceWidth]/[sourceHeight] describe the camera image that the
     * `SurfaceTexture` transform maps onto the quad. The image is never
     * stretched: [DualCameraScalingMode.CROP_TO_FILL] scales the quad so it
     * covers the region (the overflow is clipped), while
     * [DualCameraScalingMode.FIT_WITH_BARS] scales it to fit with bars.
     *
     * [rotationDegrees] is the clockwise rotation baked into the feed, so the
     * effective source aspect swaps for 90/270 — otherwise a rotated feed would
     * be cropped against the wrong aspect and appear stretched.
     */
    fun feedPlacement(
        sourceWidth: Int,
        sourceHeight: Int,
        region: PixelRect,
        mode: DualCameraScalingMode,
        mirrored: Boolean,
        rotationDegrees: Int = 0
    ): FeedPlacement {
        val srcW = sourceWidth.coerceAtLeast(1)
        val srcH = sourceHeight.coerceAtLeast(1)
        val regionW = region.width.coerceAtLeast(1)
        val regionH = region.height.coerceAtLeast(1)

        // A quarter-turn swaps the visible width/height of the source.
        val swapped = rotationDegrees.mod(180) != 0
        val effW = if (swapped) srcH else srcW
        val effH = if (swapped) srcW else srcH

        val srcAspect = effW.toFloat() / effH.toFloat()
        val regionAspect = regionW.toFloat() / regionH.toFloat()
        val sourceWider = srcAspect >= regionAspect

        val (scaleX, scaleY) = when (mode) {
            DualCameraScalingMode.CROP_TO_FILL ->
                if (sourceWider) (srcAspect / regionAspect) to 1f
                else 1f to (regionAspect / srcAspect)

            DualCameraScalingMode.FIT_WITH_BARS ->
                if (sourceWider) 1f to (regionAspect / srcAspect)
                else (srcAspect / regionAspect) to 1f
        }

        return FeedPlacement(
            region = region,
            scaleX = scaleX,
            scaleY = scaleY,
            mirrored = mirrored,
            rotationDegrees = rotationDegrees
        )
    }

    /**
     * Per-vertex texture coordinates that draw a feed rotated [rotationDegrees]
     * **clockwise on screen**, in `glDrawArrays` strip order BL, BR, TL, TR.
     *
     * Why a permutation instead of a rotation matrix: a rotation composed after
     * `SurfaceTexture.getTransformMatrix()` acts in *buffer* coordinate space,
     * and which visual direction that is depends on the transform the producer
     * emitted — so it cannot be reasoned about statically. Applying the rotation
     * to the vertex texture coordinates instead works purely in screen space and
     * cancels `getTransformMatrix()` out of the argument entirely: if `c(p)` is
     * the reference coordinate at screen position `p`, feeding `c(R(p))` samples
     * the reference frame `R⁻¹` away, i.e. rotates the *picture* by `R`.
     *
     * The reference (`rotationDegrees == 0`) is exactly the pairing the
     * single-camera recorder uses (`OverlayRenderer.CAMERA_TEX_BUFFER`), so the
     * unrotated frame is the same frame `MediaRecorder` stores and the value that
     * path passes to `setOrientationHint` is the one baked in here. The direction
     * (clockwise) matches `OverlayRenderer.applyDisplayTransform`, whose overlay
     * text is authored upright in display space and counter-rotated into the
     * buffer — the inverse of the clockwise hint rotation.
     *
     * A quarter turn also swaps which content axis runs along which screen axis,
     * which is why [feedPlacement] sizes the quad from the swapped source aspect.
     */
    fun cameraTextureCoordinates(rotationDegrees: Int): FloatArray =
        when (rotationDegrees.mod(360)) {
            // strip order: BL, BR, TL, TR
            90 -> floatArrayOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f
            )

            180 -> floatArrayOf(
                1f, 0f,
                1f, 1f,
                0f, 0f,
                0f, 1f
            )

            270 -> floatArrayOf(
                1f, 1f,
                0f, 1f,
                1f, 0f,
                0f, 0f
            )

            else -> floatArrayOf(
                0f, 1f,
                0f, 0f,
                1f, 1f,
                1f, 0f
            )
        }

    /** Resolves the complete composite for one frame. */
    fun frame(
        config: DualCameraConfig,
        width: Int,
        height: Int,
        portraitOutput: Boolean,
        frontSourceWidth: Int,
        frontSourceHeight: Int,
        backSourceWidth: Int,
        backSourceHeight: Int,
        frontRotationDegrees: Int = 0,
        backRotationDegrees: Int = 0
    ): DualCameraFrame {
        val layout = resolveLayout(config, portraitOutput)
        val (frontRegion, backRegion) = cameraRegions(config, width, height, portraitOutput)
        return DualCameraFrame(
            layout = layout,
            outputWidth = width,
            outputHeight = height,
            front = feedPlacement(
                frontSourceWidth, frontSourceHeight, frontRegion,
                config.scalingMode, config.mirrorFront, frontRotationDegrees
            ),
            back = feedPlacement(
                backSourceWidth, backSourceHeight, backRegion,
                config.scalingMode, mirrored = false, rotationDegrees = backRotationDegrees
            )
        )
    }
}
