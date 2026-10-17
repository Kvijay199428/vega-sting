package com.vega.sting.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout maths is shared by the live preview and the recorded output, so a
 * regression here would silently desynchronise the two. These tests pin the
 * split points, the placement mapping, the crop/letterbox transforms and the
 * layout resolution.
 */
class DualCameraGeometryTest {

    private val eps = 1e-4f

    private fun assertRect(expected: PixelRect, actual: PixelRect) {
        assertEquals("left", expected.left, actual.left)
        assertEquals("top", expected.top, actual.top)
        assertEquals("right", expected.right, actual.right)
        assertEquals("bottom", expected.bottom, actual.bottom)
    }

    @Test
    fun `AUTO resolves to portrait stack in portrait and split in landscape`() {
        assertEquals(
            DualCameraLayout.PORTRAIT_STACK,
            DualCameraGeometry.resolveLayout(DualCameraConfig(layout = DualCameraLayout.AUTO), portraitOutput = true)
        )
        assertEquals(
            DualCameraLayout.LANDSCAPE_SPLIT,
            DualCameraGeometry.resolveLayout(DualCameraConfig(layout = DualCameraLayout.AUTO), portraitOutput = false)
        )
    }

    @Test
    fun `explicit layout is never overridden by orientation`() {
        assertEquals(
            DualCameraLayout.LANDSCAPE_SPLIT,
            DualCameraGeometry.resolveLayout(DualCameraConfig(layout = DualCameraLayout.LANDSCAPE_SPLIT), portraitOutput = true)
        )
        assertEquals(
            DualCameraLayout.PORTRAIT_STACK,
            DualCameraGeometry.resolveLayout(DualCameraConfig(layout = DualCameraLayout.PORTRAIT_STACK), portraitOutput = false)
        )
    }

    @Test
    fun `isPortraitOutput treats square as portrait`() {
        assertTrue(DualCameraGeometry.isPortraitOutput(1080, 1920))
        assertTrue(DualCameraGeometry.isPortraitOutput(1000, 1000))
        assertFalse(DualCameraGeometry.isPortraitOutput(1920, 1080))
    }

    @Test
    fun `portrait split divides height and shares an edge`() {
        val (first, second) = DualCameraGeometry.splitRegions(1080, 1920, portraitOutput = true, firstFraction = 0.5f)
        assertRect(PixelRect(0, 0, 1080, 960), first)
        assertRect(PixelRect(0, 960, 1080, 1920), second)
        assertEquals(first.bottom, second.top)
        assertEquals(1920, first.height + second.height)
    }

    @Test
    fun `landscape split divides width and shares an edge`() {
        val (first, second) = DualCameraGeometry.splitRegions(1920, 1080, portraitOutput = false, firstFraction = 0.5f)
        assertRect(PixelRect(0, 0, 960, 1080), first)
        assertRect(PixelRect(960, 0, 1920, 1080), second)
        assertEquals(first.right, second.left)
        assertEquals(1920, first.width + second.width)
    }

    @Test
    fun `split ratio 40_60 gives the first region the smaller share`() {
        val (first, second) = DualCameraGeometry.splitRegions(1000, 1000, portraitOutput = true, firstFraction = 0.4f)
        assertEquals(400, first.height)
        assertEquals(600, second.height)
    }

    @Test
    fun `split ratio 60_40 gives the first region the larger share`() {
        val (first, second) = DualCameraGeometry.splitRegions(1000, 1000, portraitOutput = false, firstFraction = 0.6f)
        assertEquals(600, first.width)
        assertEquals(400, second.width)
    }

    @Test
    fun `portrait placement maps front to the top when frontOnTop`() {
        val config = DualCameraConfig(frontOnTop = true)
        val (front, back) = DualCameraGeometry.cameraRegions(config, 1080, 1920, portraitOutput = true)
        assertEquals(0, front.top)
        assertEquals(960, back.top)
    }

    @Test
    fun `portrait placement maps front to the bottom when not frontOnTop`() {
        val config = DualCameraConfig(frontOnTop = false)
        val (front, back) = DualCameraGeometry.cameraRegions(config, 1080, 1920, portraitOutput = true)
        assertEquals(960, front.top)
        assertEquals(0, back.top)
    }

    @Test
    fun `landscape placement maps front to the left when frontOnLeft`() {
        val config = DualCameraConfig(frontOnLeft = true)
        val (front, back) = DualCameraGeometry.cameraRegions(config, 1920, 1080, portraitOutput = false)
        assertEquals(0, front.left)
        assertEquals(960, back.left)
    }

    @Test
    fun `landscape placement maps front to the right when not frontOnLeft`() {
        val config = DualCameraConfig(frontOnLeft = false)
        val (front, back) = DualCameraGeometry.cameraRegions(config, 1920, 1080, portraitOutput = false)
        assertEquals(960, front.left)
        assertEquals(0, back.left)
    }

    @Test
    fun `crop to fill scales the wider source horizontally and keeps the region`() {
        val region = PixelRect(0, 0, 900, 900)
        val placement = DualCameraGeometry.feedPlacement(
            sourceWidth = 1600, sourceHeight = 900, region = region,
            mode = DualCameraScalingMode.CROP_TO_FILL, mirrored = false
        )
        assertRect(region, placement.region)
        assertEquals(1.7777778f, placement.scaleX, eps)
        assertEquals(1f, placement.scaleY, eps)
        assertFalse(placement.mirrored)
    }

    @Test
    fun `crop to fill scales the taller source vertically`() {
        val region = PixelRect(0, 0, 900, 900)
        val placement = DualCameraGeometry.feedPlacement(
            sourceWidth = 900, sourceHeight = 1600, region = region,
            mode = DualCameraScalingMode.CROP_TO_FILL, mirrored = false
        )
        assertEquals(1f, placement.scaleX, eps)
        assertEquals(1.7777778f, placement.scaleY, eps)
    }

    @Test
    fun `fit with bars shrinks a wide source vertically inside the region`() {
        val region = PixelRect(0, 0, 900, 900)
        val placement = DualCameraGeometry.feedPlacement(
            sourceWidth = 1600, sourceHeight = 900, region = region,
            mode = DualCameraScalingMode.FIT_WITH_BARS, mirrored = false
        )
        assertEquals(1f, placement.scaleX, eps)
        assertEquals(0.5625f, placement.scaleY, eps)
    }

    @Test
    fun `fit with bars shrinks a tall source horizontally inside the region`() {
        val region = PixelRect(0, 0, 900, 900)
        val placement = DualCameraGeometry.feedPlacement(
            sourceWidth = 900, sourceHeight = 1600, region = region,
            mode = DualCameraScalingMode.FIT_WITH_BARS, mirrored = false
        )
        assertEquals(0.5625f, placement.scaleX, eps)
        assertEquals(1f, placement.scaleY, eps)
    }

    @Test
    fun `crop and fit preserve the source aspect ratio`() {
        val region = PixelRect(0, 0, 1200, 900)
        val regionAspect = region.width.toFloat() / region.height
        for (mode in DualCameraScalingMode.entries) {
            for ((sw, sh) in listOf(1600 to 900, 900 to 1600, 1080 to 1080)) {
                val p = DualCameraGeometry.feedPlacement(sw, sh, region, mode, mirrored = false)
                // Drawn width / drawn height == source aspect.
                val drawnAspect = (p.scaleX * region.width) / (p.scaleY * region.height)
                assertEquals(sw.toFloat() / sh, drawnAspect, 1e-3f)
                // Cover fills the region; contain never overflows it.
                when (mode) {
                    DualCameraScalingMode.CROP_TO_FILL -> {
                        assertTrue(p.scaleX >= 1f - eps)
                        assertTrue(p.scaleY >= 1f - eps)
                    }
                    DualCameraScalingMode.FIT_WITH_BARS -> {
                        assertTrue(p.scaleX <= 1f + eps)
                        assertTrue(p.scaleY <= 1f + eps)
                    }
                }
                assertTrue(regionAspect > 0f)
            }
        }
    }

    @Test
    fun `mirroring is reported for the front feed`() {
        val region = PixelRect(0, 0, 900, 900)
        val placement = DualCameraGeometry.feedPlacement(
            sourceWidth = 1600, sourceHeight = 900, region = region,
            mode = DualCameraScalingMode.CROP_TO_FILL, mirrored = true
        )
        assertTrue(placement.mirrored)
        // Mirroring never changes the fit.
        assertEquals(1.7777778f, placement.scaleX, eps)
        assertEquals(1f, placement.scaleY, eps)
    }

    @Test
    fun `frame resolves layout and both placements for a portrait composite`() {
        val config = DualCameraConfig(
            layout = DualCameraLayout.AUTO, frontOnTop = true, frontOnLeft = true,
            splitRatio = DualCameraSplitRatio.RATIO_50_50,
            scalingMode = DualCameraScalingMode.CROP_TO_FILL
        )
        val frame = DualCameraGeometry.frame(
            config = config, width = 1080, height = 1920, portraitOutput = true,
            frontSourceWidth = 1600, frontSourceHeight = 900,
            backSourceWidth = 1600, backSourceHeight = 900
        )
        assertEquals(DualCameraLayout.PORTRAIT_STACK, frame.layout)
        assertEquals(1080, frame.outputWidth)
        assertEquals(1920, frame.outputHeight)
        assertRect(PixelRect(0, 0, 1080, 960), frame.front.region)
        assertRect(PixelRect(0, 960, 1080, 1920), frame.back.region)
    }

    @Test
    fun `frame resolves a landscape composite with front on the left`() {
        val config = DualCameraConfig(
            layout = DualCameraLayout.AUTO, frontOnTop = true, frontOnLeft = true,
            splitRatio = DualCameraSplitRatio.RATIO_50_50
        )
        val frame = DualCameraGeometry.frame(
            config = config, width = 1920, height = 1080, portraitOutput = false,
            frontSourceWidth = 1600, frontSourceHeight = 900,
            backSourceWidth = 1600, backSourceHeight = 900
        )
        assertEquals(DualCameraLayout.LANDSCAPE_SPLIT, frame.layout)
        assertRect(PixelRect(0, 0, 960, 1080), frame.front.region)
        assertRect(PixelRect(960, 0, 1920, 1080), frame.back.region)
    }

    @Test
    fun `a quarter turn swaps the effective source aspect used for cropping`() {
        val region = PixelRect(0, 0, 900, 900)
        // 1600x900 landscape source; rotated 90 it is a 900x1600 portrait image,
        // so CROP_TO_FILL must now scale vertically, not horizontally.
        val rotated = DualCameraGeometry.feedPlacement(
            sourceWidth = 1600, sourceHeight = 900, region = region,
            mode = DualCameraScalingMode.CROP_TO_FILL, mirrored = false, rotationDegrees = 90
        )
        assertEquals(1f, rotated.scaleX, eps)
        assertEquals(1.7777778f, rotated.scaleY, eps)
        assertEquals(90, rotated.rotationDegrees)
    }

    @Test
    fun `a half turn keeps the source aspect`() {
        val region = PixelRect(0, 0, 900, 900)
        val rotated = DualCameraGeometry.feedPlacement(
            sourceWidth = 1600, sourceHeight = 900, region = region,
            mode = DualCameraScalingMode.CROP_TO_FILL, mirrored = false, rotationDegrees = 180
        )
        assertEquals(1.7777778f, rotated.scaleX, eps)
        assertEquals(1f, rotated.scaleY, eps)
        assertEquals(180, rotated.rotationDegrees)
    }

    @Test
    fun `unrotated placement reports zero rotation`() {
        val placement = DualCameraGeometry.feedPlacement(
            sourceWidth = 1600, sourceHeight = 900, region = PixelRect(0, 0, 900, 900),
            mode = DualCameraScalingMode.CROP_TO_FILL, mirrored = false
        )
        assertEquals(0, placement.rotationDegrees)
    }

    @Test
    fun `frame carries per-feed rotation into each placement`() {
        val frame = DualCameraGeometry.frame(
            config = DualCameraConfig(),
            width = 1080, height = 1920, portraitOutput = true,
            frontSourceWidth = 1280, frontSourceHeight = 720,
            backSourceWidth = 1280, backSourceHeight = 720,
            frontRotationDegrees = 270,
            backRotationDegrees = 90
        )
        assertEquals(270, frame.front.rotationDegrees)
        assertEquals(90, frame.back.rotationDegrees)
    }

    // ---- Rotation direction -------------------------------------------
    //
    // The corner names in screen space, in glDrawArrays strip order.
    private val corners = listOf(
        "BL" to (0 to 0),
        "BR" to (1 to 0),
        "TL" to (0 to 1),
        "TR" to (1 to 1)
    )

    /** The single-camera recorder's pairing (OverlayRenderer.CAMERA_TEX_BUFFER). */
    private fun referenceCoordinate(x: Int, y: Int): Pair<Float, Float> =
        (if (y == 1) 1f else 0f) to (if (x == 0) 1f else 0f)

    /** One counter-clockwise step: TL→BL, BL→BR, BR→TR, TR→TL. */
    private fun counterClockwise(x: Int, y: Int): Pair<Int, Int> =
        when {
            x == 0 && y == 1 -> 0 to 0 // TL -> BL
            x == 0 && y == 0 -> 1 to 0 // BL -> BR
            x == 1 && y == 0 -> 1 to 1 // BR -> TR
            else -> 0 to 1             // TR -> TL
        }

    @Test
    fun `zero rotation is byte-identical to the single-camera pairing`() {
        // OverlayRenderer.CAMERA_TEX_BUFFER
        val expected = listOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        assertEquals(expected, DualCameraGeometry.cameraTextureCoordinates(0).toList())
        assertEquals(expected, DualCameraGeometry.cameraTextureCoordinates(360).toList())
    }

    /**
     * Rotating the picture clockwise by R moves the content that sat at a corner
     * one step clockwise, so the vertex sitting at that corner must carry the
     * coordinate of the corner R counter-clockwise away. Deriving the expectation
     * from that rule (instead of re-listing the table) pins the *direction*, which
     * is the part that would silently produce upside-down recordings.
     */
    @Test
    fun `each quarter turn rotates the picture clockwise by the right amount`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val actual = DualCameraGeometry.cameraTextureCoordinates(rotation)
            val steps = (rotation / 90) % 4

            corners.forEachIndexed { i, (_, corner) ->
                var source = corner
                repeat(steps) { source = counterClockwise(source.first, source.second) }
                val (expectedU, expectedV) = referenceCoordinate(source.first, source.second)

                assertEquals("rotation=$rotation vertex=$i u", expectedU, actual[i * 2], 0f)
                assertEquals("rotation=$rotation vertex=$i v", expectedV, actual[i * 2 + 1], 0f)
            }
        }
    }

    @Test
    fun `every rotation is a valid permutation of the four corners`() {
        for (rotation in listOf(0, 90, 180, 270, 450, -90)) {
            val c = DualCameraGeometry.cameraTextureCoordinates(rotation)
            val seen = (0 until 4).map { c[it * 2] to c[it * 2 + 1] }.toSet()
            assertEquals("rotation=$rotation", 4, seen.size)
            assertEquals(
                "rotation=$rotation",
                setOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f),
                seen
            )
        }
    }
}
