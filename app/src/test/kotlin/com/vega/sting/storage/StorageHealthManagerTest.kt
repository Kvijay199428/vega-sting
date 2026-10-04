package com.vega.sting.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale


class StorageHealthManagerTest {

    private fun format(bytes: Long): String =
        StorageHealthManager.formatFileSize(bytes, Locale.US)

    @Test
    fun `zero bytes renders as bytes not a negative scale`() {
        assertEquals("0 B", format(0))
    }

    @Test
    fun `negative byte counts never render as negative amounts`() {
        assertEquals("0 B", format(-1))
        assertEquals("0 B", format(Long.MIN_VALUE))
    }

    @Test
    fun `value below one kilobyte stays in bytes`() {
        assertEquals("1 B", format(1))
        assertEquals("1023 B", format(1023))
    }

    @Test
    fun `one kilobyte boundary rolls over to KB`() {
        assertEquals("1 KB", format(1024))
    }

    @Test
    fun `kibibyte values are rounded without decimals`() {
        assertEquals("3 KB", format(2 * 1024 + 512))
        assertEquals("1023 KB", format(1023 * 1024 + 250))
    }

    @Test
    fun `one megabyte boundary rolls over to MB`() {
        assertEquals("1.0 MB", format(1024 * 1024))
    }

    @Test
    fun `megabyte values keep one decimal place`() {
        assertEquals("1.5 MB", format(Math.round(1.5 * 1024 * 1024)))
    }

    @Test
    fun `one gigabyte boundary rolls over to GB`() {
        assertEquals("1.00 GB", format(1024L * 1024 * 1024))
    }

    @Test
    fun `gigabyte values keep two decimal places`() {
        assertEquals("1.50 GB", format(1024L * 1024 * 1024 + 512L * 1024 * 1024))
    }

    @Test
    fun `free-space readout is two decimal gigabytes`() {
        assertEquals(
            "1.00 GB",
            StorageHealthManager.formatBytes(1024L * 1024 * 1024)
        )
    }

    @Test
    fun `formatting is identical under a pinned locale`() {
        val german = Locale.GERMANY
        assertEquals(
            StorageHealthManager.formatFileSize(2 * 1024 * 1024, Locale.US),
            StorageHealthManager.formatFileSize(2 * 1024 * 1024, german).replace(',', '.')
        )
    }
}