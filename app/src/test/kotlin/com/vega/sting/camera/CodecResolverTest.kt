package com.vega.sting.camera

import org.junit.Assert.assertEquals
import org.junit.Test


class CodecResolverTest {

    private val hwHevc1080p = VideoEncoderInfo(
        name = "c2.vendor.hevc.enc",
        mimeType = "video/hevc",
        isHardwareAccelerated = true,
        maxWidth = 3840,
        maxHeight = 2160
    )

    private val hwHevc720p = VideoEncoderInfo(
        name = "c2.vendor.hevc.enc",
        mimeType = "video/hevc",
        isHardwareAccelerated = true,
        maxWidth = 1280,
        maxHeight = 720
    )

    private val swHevc = VideoEncoderInfo(
        name = "c2.android.hevc.enc",
        mimeType = "video/hevc",
        isHardwareAccelerated = false,
        maxWidth = 4096,
        maxHeight = 2160
    )

    private val hwAvc = VideoEncoderInfo(
        name = "c2.vendor.avc.enc",
        mimeType = "video/avc",
        isHardwareAccelerated = true,
        maxWidth = 3840,
        maxHeight = 2160
    )

    

    @Test
    fun `h264 request returns h264 even when hevc exists`() {
        assertEquals(
            "H264",
            resolveEffectiveVideoCodec("H.264", listOf(hwHevc1080p))
        )
    }

    @Test
    fun `h264 request returns h264 when nothing exists`() {
        assertEquals("H264", resolveEffectiveVideoCodec("H.264", emptyList()))
    }

    @Test
    fun `unknown requested codec falls back to h264`() {
        assertEquals(
            "H264",
            resolveEffectiveVideoCodec("H.266", listOf(hwHevc1080p))
        )
    }

    

    @Test
    fun `h265 request is honored with hardware hevc at 1080p or better`() {
        assertEquals(
            "HEVC",
            resolveEffectiveVideoCodec("H.265", listOf(hwHevc1080p, hwAvc))
        )
    }

    @Test
    fun `h265 request falls back to h264 without any hevc encoder`() {
        assertEquals(
            "H264",
            resolveEffectiveVideoCodec("H.265", listOf(hwAvc))
        )
    }

    @Test
    fun `h265 request falls back to h264 with software-only hevc`() {
        assertEquals(
            "H264",
            resolveEffectiveVideoCodec("H.265", listOf(swHevc))
        )
    }

    @Test
    fun `h265 request falls back to h264 when the hardware encoder is under 1080p`() {
        assertEquals(
            "H264",
            resolveEffectiveVideoCodec("H.265", listOf(hwHevc720p))
        )
    }

    @Test
    fun `h265 request falls back to h264 with no encoders at all`() {
        assertEquals("H264", resolveEffectiveVideoCodec("H.265", emptyList()))
    }

    

    @Test
    fun `hardware hevc that is too small does not qualify alongside a software 4k encoder`() {
        assertEquals(
            "H264",
            resolveEffectiveVideoCodec("H.265", listOf(hwHevc720p, swHevc))
        )
    }
}