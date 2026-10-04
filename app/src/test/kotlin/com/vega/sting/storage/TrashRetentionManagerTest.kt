package com.vega.sting.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit


class TrashRetentionManagerTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `retention period is thirty days`() {
        assertEquals(TimeUnit.DAYS.toMillis(30), TrashRetentionManager.RETENTION_PERIOD_MS)
    }

    @Test
    fun `cutoff is now minus the retention period`() {
        assertEquals(
            now - TimeUnit.DAYS.toMillis(30),
            TrashRetentionManager.computeCutoff(now)
        )
    }

    @Test
    fun `trash at exactly the cutoff age is expired`() {
        val deletedAt = now - TimeUnit.DAYS.toMillis(30)
        assertTrue(TrashRetentionManager.isExpired(deletedAt, now))
    }

    @Test
    fun `trash just inside retention is not expired`() {
        val deletedAt = now - TimeUnit.DAYS.toMillis(30) + 1
        assertFalse(TrashRetentionManager.isExpired(deletedAt, now))
    }

    @Test
    fun `trash older than retention is expired`() {
        val deletedAt = now - TimeUnit.DAYS.toMillis(31)
        assertTrue(TrashRetentionManager.isExpired(deletedAt, now))
    }

    @Test
    fun `thirty one days of the default cutoff agree with the explicit now`() {
        assertEquals(
            TrashRetentionManager.computeCutoff(),
            System.currentTimeMillis() - TrashRetentionManager.RETENTION_PERIOD_MS
        )
    }
}