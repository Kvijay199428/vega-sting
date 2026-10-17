package com.vega.sting.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pre-download free-space policy. Pure arithmetic, no Android
 * framework required.
 */
class StoragePreflightTest {

    private val mib = 1024L * 1024L

    @Test
    fun `margin is never below the 20 MiB floor`() {
        // Small APKs still reserve the absolute floor.
        assertEquals(20L * mib, StoragePreflight.marginBytes(1L))
        assertEquals(20L * mib, StoragePreflight.marginBytes(50L * mib))
    }

    @Test
    fun `margin becomes ten percent once that exceeds the floor`() {
        // 300 MiB / 10 = 30 MiB > 20 MiB floor.
        assertEquals(30L * mib, StoragePreflight.marginBytes(300L * mib))
    }

    @Test
    fun `required is the apk size plus its margin`() {
        val size = 300L * mib
        assertEquals(size + 30L * mib, StoragePreflight.requiredBytes(size))
    }

    @Test
    fun `an unknown size is treated as a conservative estimate`() {
        // Zero / negative means "unknown" and must not be treated literally.
        assertEquals(
            StoragePreflight.requiredBytes(StoragePreflight.UNKNOWN_APK_ESTIMATE_BYTES),
            StoragePreflight.requiredBytes(0L)
        )
        assertEquals(
            StoragePreflight.requiredBytes(StoragePreflight.UNKNOWN_APK_ESTIMATE_BYTES),
            StoragePreflight.requiredBytes(-1L)
        )
    }

    @Test
    fun `hasRoom requires the full requirement including margin`() {
        val size = 100L * mib
        val required = StoragePreflight.requiredBytes(size)
        assertTrue(StoragePreflight.hasRoom(required, size))
        assertFalse(StoragePreflight.hasRoom(required - 1L, size))
    }

    @Test
    fun `hasRoom is false when only the raw apk would fit`() {
        val size = 100L * mib
        // Exactly the APK size but none of the 20 MiB margin.
        assertFalse(StoragePreflight.hasRoom(size, size))
    }
}
