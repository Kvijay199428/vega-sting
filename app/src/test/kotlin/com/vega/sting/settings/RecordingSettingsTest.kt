package com.vega.sting.settings

import com.vega.sting.storage.StoragePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored preferences are untrusted input (an old build, a restored backup,
 * a corrupted DataStore). [RecordingSettings.sanitize] must always produce a
 * usable snapshot whose limits respect the safety bounds.
 */
class RecordingSettingsTest {

    private val mb = 1024L * 1024L
    private val gb = 1024L * mb

    @Test
    fun `defaults match the documented values`() {
        val defaults = RecordingSettings.DEFAULTS
        assertTrue(defaults.splitRecordingEnabled)
        assertEquals(1L * gb, defaults.videoSegmentLimitBytes)
        assertEquals(100L * mb, defaults.audioSegmentLimitBytes)
        assertEquals(StoragePolicy.DEFAULT_RESERVE_BYTES, defaults.minimumFreeSpaceBytes)
        assertEquals(StoragePolicy.DEFAULT_WARNING_BYTES, defaults.lowStorageWarningBytes)
        assertTrue(defaults.audioFallbackEnabled)
    }

    @Test
    fun `out-of-range limits are clamped, not rejected`() {
        val sanitized = RecordingSettings.sanitize(
            RecordingSettings.DEFAULTS.copy(
                videoSegmentLimitBytes = 1L,
                audioSegmentLimitBytes = Long.MAX_VALUE,
                minimumFreeSpaceBytes = 1L
            )
        )
        assertEquals(StoragePolicy.MIN_SEGMENT_BYTES, sanitized.videoSegmentLimitBytes)
        assertEquals(StoragePolicy.MAX_SEGMENT_BYTES, sanitized.audioSegmentLimitBytes)
        assertEquals(StoragePolicy.MIN_RESERVE_BYTES, sanitized.minimumFreeSpaceBytes)
    }

    @Test
    fun `a warning threshold below the reserve is raised to the reserve floor`() {
        val sanitized = RecordingSettings.sanitize(
            RecordingSettings.DEFAULTS.copy(lowStorageWarningBytes = 0L)
        )
        assertEquals(StoragePolicy.MIN_RESERVE_BYTES, sanitized.lowStorageWarningBytes)
    }

    @Test
    fun `valid values are preserved`() {
        val input = RecordingSettings(
            splitRecordingEnabled = false,
            videoSegmentLimitBytes = 2L * gb,
            audioSegmentLimitBytes = 250L * mb,
            minimumFreeSpaceBytes = 300L * mb,
            lowStorageWarningBytes = 2L * gb,
            audioFallbackEnabled = false
        )
        val sanitized = RecordingSettings.sanitize(input)
        assertEquals(input, sanitized)
        assertFalse(sanitized.splitRecordingEnabled)
        assertFalse(sanitized.audioFallbackEnabled)
    }
}
