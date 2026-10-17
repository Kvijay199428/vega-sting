package com.vega.sting.orientation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the timeout/fallback behaviour of the first-reading wait: the latch must
 * release as soon as a reading arrives, but must never block a recording thread
 * indefinitely when no sensor reading is available.
 */
class ReadingGateTest {

    @Test
    fun `await returns true once signalled`() {
        val gate = ReadingGate()
        gate.signal()
        assertTrue(gate.await(500))
    }

    @Test
    fun `await returns false on timeout when never signalled`() {
        val gate = ReadingGate()
        assertFalse(gate.await(50))
    }

    @Test
    fun `signal after reset releases the new wait`() {
        val gate = ReadingGate()
        gate.signal()
        assertTrue(gate.await(500))

        gate.reset()
        assertFalse(gate.await(20))
        gate.signal()
        assertTrue(gate.await(500))
    }

    @Test
    fun `signal is idempotent`() {
        val gate = ReadingGate()
        gate.signal()
        gate.signal()
        assertTrue(gate.await(500))
    }
}
