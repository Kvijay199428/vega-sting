package com.vega.sting.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the checksum helpers used to verify a downloaded APK before
 * it is offered to the Package Installer.
 */
class ApkValidatorTest {

    private fun tempFile(bytes: ByteArray): File {
        val file = File.createTempFile("vega-apk", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun `sha256 matches the known digest of the input`() {
        val file = tempFile("abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ApkValidator.sha256(file)
        )
    }

    @Test
    fun `verifyChecksum accepts the correct digest`() {
        val file = tempFile("abc".toByteArray())
        assertTrue(ApkValidator.verifyChecksum(file, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
    }

    @Test
    fun `verifyChecksum rejects a wrong digest`() {
        val file = tempFile("abc".toByteArray())
        assertFalse(ApkValidator.verifyChecksum(file, "b".repeat(64)))
    }

    @Test
    fun `verifyChecksum rejects a missing or malformed digest`() {
        val file = tempFile("abc".toByteArray())
        assertFalse(ApkValidator.verifyChecksum(file, null))
        assertFalse(ApkValidator.verifyChecksum(file, ""))
        assertFalse(ApkValidator.verifyChecksum(file, "deadbeef"))
    }

    @Test
    fun `isSha256 validates digest shape`() {
        assertTrue(ApkValidator.isSha256("a".repeat(64)))
        assertTrue(ApkValidator.isSha256("ABCDEF0123456789".repeat(4)))
        assertFalse(ApkValidator.isSha256("a".repeat(63)))
        assertFalse(ApkValidator.isSha256("g".repeat(64)))
        assertFalse(ApkValidator.isSha256(null))
    }

    @Test
    fun `versionGuardReason allows a strictly newer code`() {
        assertEquals(null, ApkValidator.versionGuardReason(19, 18, allowDowngrade = false))
    }

    @Test
    fun `versionGuardReason explains an equal code as no newer build`() {
        val reason = ApkValidator.versionGuardReason(18, 18, allowDowngrade = false)
        assertTrue(reason != null && reason.contains("same as the installed build"))
    }

    @Test
    fun `versionGuardReason explains a lower code as a downgrade`() {
        val reason = ApkValidator.versionGuardReason(17, 18, allowDowngrade = false)
        assertTrue(reason != null && reason.contains("older than the installed build"))
    }

    @Test
    fun `versionGuardReason permits an equal or lower code when a downgrade is allowed`() {
        assertEquals(null, ApkValidator.versionGuardReason(18, 18, allowDowngrade = true))
        assertEquals(null, ApkValidator.versionGuardReason(17, 18, allowDowngrade = true))
    }
}
