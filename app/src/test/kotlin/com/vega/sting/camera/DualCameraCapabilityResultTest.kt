package com.vega.sting.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DualCameraCapabilityResult.concurrentPairAllowed] must be order independent
 * and must require *both* cameras to appear in the same platform-set. A device
 * that lists a front/back pair is the only case that may proceed.
 */
class DualCameraCapabilityResultTest {

    @Test
    fun `pair is allowed regardless of order`() {
        val sets = listOf(setOf("1", "0"))
        assertTrue(DualCameraCapabilityResult.concurrentPairAllowed(sets, "1", "0"))
        assertTrue(DualCameraCapabilityResult.concurrentPairAllowed(sets, "0", "1"))
    }

    @Test
    fun `pair is rejected when the two cameras are listed separately`() {
        val sets = listOf(setOf("0", "2"), setOf("1", "3"))
        assertFalse(DualCameraCapabilityResult.concurrentPairAllowed(sets, "0", "1"))
    }

    @Test
    fun `pair is rejected when the concurrent list is empty`() {
        assertFalse(DualCameraCapabilityResult.concurrentPairAllowed(emptyList(), "0", "1"))
    }

    @Test
    fun `larger sets that contain both cameras are accepted`() {
        val sets = listOf(setOf("0", "1", "2"))
        assertTrue(DualCameraCapabilityResult.concurrentPairAllowed(sets, "0", "1"))
    }

    @Test
    fun `a set that contains only one camera is rejected`() {
        val sets = listOf(setOf("0"))
        assertFalse(DualCameraCapabilityResult.concurrentPairAllowed(sets, "0", "1"))
    }

    @Test
    fun `canOfferDualCamera requires a ready recording-capable result`() {
        val ready = DualCameraCapabilityResult(
            status = DualCameraCapabilityStatus.READY,
            hasCameraPermission = true,
            isRecordingSupported = true
        )
        assertTrue(ready.canOfferDualCamera)

        assertFalse(ready.copy(hasCameraPermission = false).canOfferDualCamera)
        assertFalse(ready.copy(isRecordingSupported = false).canOfferDualCamera)
        assertFalse(
            ready.copy(status = DualCameraCapabilityStatus.CONCURRENT_UNSUPPORTED).canOfferDualCamera
        )
    }
}
