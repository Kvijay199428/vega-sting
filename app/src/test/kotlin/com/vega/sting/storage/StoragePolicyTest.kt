package com.vega.sting.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the storage decision table. No Android classes are
 * touched, so these run on the normal `test` task.
 */
class StoragePolicyTest {

    private val mb = 1024L * 1024L
    private val gb = 1024L * mb

    private val warning = 1L * gb
    private val reserve = 100L * mb
    private val critical = reserve + StoragePolicy.VIDEO_SAFETY_HEADROOM_BYTES
    private val margin = StoragePolicy.DEFAULT_AUDIO_FALLBACK_MARGIN_BYTES

    // ---- statusFor ---------------------------------------------------------

    @Test
    fun `plenty of space is normal`() {
        assertEquals(
            StorageStatus.NORMAL,
            StoragePolicy.statusFor(warning + 1, warning, reserve, true)
        )
    }

    @Test
    fun `warn band reports low`() {
        assertEquals(
            StorageStatus.LOW,
            StoragePolicy.statusFor(warning, warning, reserve, true)
        )
        assertEquals(
            StorageStatus.LOW,
            StoragePolicy.statusFor(critical + 1, warning, reserve, true)
        )
    }

    @Test
    fun `video-unsafe band reports critical`() {
        assertEquals(
            StorageStatus.CRITICAL,
            StoragePolicy.statusFor(critical, warning, reserve, true)
        )
        assertEquals(
            StorageStatus.CRITICAL,
            StoragePolicy.statusFor(reserve + 1, warning, reserve, true)
        )
    }

    @Test
    fun `at or below the reserve reports reserve reached`() {
        assertEquals(
            StorageStatus.RESERVE_REACHED,
            StoragePolicy.statusFor(reserve, warning, reserve, true)
        )
        assertEquals(
            StorageStatus.RESERVE_REACHED,
            StoragePolicy.statusFor(0L, warning, reserve, true)
        )
    }

    @Test
    fun `unavailable volume is never treated as normal`() {
        assertEquals(
            StorageStatus.STORAGE_UNAVAILABLE,
            StoragePolicy.statusFor(0L, warning, reserve, available = false)
        )
        assertEquals(
            StorageStatus.STORAGE_UNAVAILABLE,
            StoragePolicy.statusFor(500L * gb, warning, reserve, available = false)
        )
    }

    @Test
    fun `untrusted reading reports error`() {
        assertEquals(
            StorageStatus.STORAGE_ERROR,
            StoragePolicy.statusFor(-1L, warning, reserve, available = true)
        )
    }

    // ---- individual decisions ---------------------------------------------

    @Test
    fun `recording stops at the reserve and when storage is unreadable`() {
        assertTrue(StoragePolicy.shouldStopRecording(reserve, reserve))
        assertTrue(StoragePolicy.shouldStopRecording(reserve - 1, reserve))
        assertTrue(StoragePolicy.shouldStopRecording(-1L, reserve))
        assertFalse(StoragePolicy.shouldStopRecording(reserve + 1, reserve))
    }

    @Test
    fun `video may start above the reserve but needs confirmation in the warn band`() {
        assertTrue(StoragePolicy.canStartVideo(reserve + 1, reserve))
        assertFalse(StoragePolicy.canStartVideo(reserve, reserve))

        assertTrue(StoragePolicy.videoRequiresConfirmation(warning, warning, reserve))
        assertTrue(StoragePolicy.videoRequiresConfirmation(reserve + 1, warning, reserve))
        assertFalse(StoragePolicy.videoRequiresConfirmation(warning + 1, warning, reserve))
        assertFalse(StoragePolicy.videoRequiresConfirmation(reserve, warning, reserve))
    }

    @Test
    fun `video continues only above the critical headroom`() {
        assertTrue(StoragePolicy.canContinueVideo(critical + 1, reserve))
        assertFalse(StoragePolicy.canContinueVideo(critical, reserve))
    }

    @Test
    fun `audio needs the reserve plus a safety margin`() {
        assertTrue(StoragePolicy.canStartAudio(reserve + margin + 1, reserve, margin))
        assertFalse(StoragePolicy.canStartAudio(reserve + margin, reserve, margin))
        assertFalse(StoragePolicy.canStartAudio(reserve + 1, reserve, margin))
    }

    @Test
    fun `a disabled limit never rotates`() {
        assertFalse(StoragePolicy.shouldRotateFile(5L * gb, 0L))
        assertTrue(StoragePolicy.shouldRotateFile(1L * gb, 1L * gb))
        assertFalse(StoragePolicy.shouldRotateFile(1L * gb - 1, 1L * gb))
    }

    // ---- clamping ----------------------------------------------------------

    @Test
    fun `segment limits are clamped to the safe range`() {
        assertEquals(StoragePolicy.MIN_SEGMENT_BYTES, StoragePolicy.clampSegmentLimit(1L))
        assertEquals(StoragePolicy.MAX_SEGMENT_BYTES, StoragePolicy.clampSegmentLimit(Long.MAX_VALUE))
        assertEquals(1L * gb, StoragePolicy.clampSegmentLimit(1L * gb))
        assertTrue(StoragePolicy.isValidSegmentLimit(1L * gb))
        assertFalse(StoragePolicy.isValidSegmentLimit(1L))
    }

    @Test
    fun `reserves are clamped to the safe range`() {
        assertEquals(StoragePolicy.MIN_RESERVE_BYTES, StoragePolicy.clampReserve(0L))
        assertEquals(StoragePolicy.MAX_RESERVE_BYTES, StoragePolicy.clampReserve(50L * gb))
        assertTrue(StoragePolicy.isValidReserve(reserve))
        assertFalse(StoragePolicy.isValidReserve(1L))
    }
}
