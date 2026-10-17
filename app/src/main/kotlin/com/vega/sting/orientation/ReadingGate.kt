package com.vega.sting.orientation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One-shot gate that releases when [signal] is called, or reports a timeout via
 * [await]. Extracted from [OrientationManager] so the first-reading wait and its
 * fallback can be exercised deterministically on the JVM.
 *
 * Thread-safe: [signal] is called from the sensor listener thread while [await]
 * blocks a recording thread.
 */
internal class ReadingGate {

    @Volatile
    private var latch = CountDownLatch(1)

    /** Re-arms the gate for a new session. */
    fun reset() {
        latch = CountDownLatch(1)
    }

    /** Releases every [await] caller. Safe to call more than once. */
    fun signal() {
        latch.countDown()
    }

    /** True if the gate was signalled within [timeoutMs]; false on timeout. */
    fun await(timeoutMs: Long): Boolean {
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
