package com.vega.sting.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure helpers of [ApkStore]: managed asset-name parsing and
 * filename sanitisation. These do not touch storage or the Android framework.
 */
class ApkStoreTest {

    @Test
    fun `stable asset name yields its version name`() {
        assertEquals("2.0.5", ApkStore.versionNameFromAsset("vega-sting-stable-2.0.5.apk"))
    }

    @Test
    fun `debug asset name keeps the dev suffix`() {
        assertEquals("2.0.6-dev.1", ApkStore.versionNameFromAsset("vega-sting-debug-2.0.6-dev.1.apk"))
    }

    @Test
    fun `unmanaged or malformed names are ignored`() {
        assertNull(ApkStore.versionNameFromAsset("other-app-1.0.apk"))
        assertNull(ApkStore.versionNameFromAsset("vega-sting-stable-2.0.5.zip"))
        assertNull(ApkStore.versionNameFromAsset("vega-sting-2.0.5.apk"))
        assertNull(ApkStore.versionNameFromAsset("vega-sting-stable-.apk"))
    }

    @Test
    fun `sanitize strips path and unsafe characters`() {
        assertEquals("vega-sting-stable-2.0.5.apk", ApkStore.sanitize("vega-sting-stable-2.0.5.apk"))
        assertEquals(".._.._etc_passwd", ApkStore.sanitize("../../etc/passwd"))
        assertEquals("a_b_c.apk", ApkStore.sanitize("a b\\c.apk"))
    }

    @Test
    fun `sanitize never returns an empty name`() {
        assertEquals("vega-sting.apk", ApkStore.sanitize(""))
        assertEquals("___", ApkStore.sanitize("///"))
    }

    @Test
    fun `the public folder is the documented download folder name`() {
        // RELATIVE_PATH is derived from Environment.DIRECTORY_DOWNLOADS, which is
        // mock-null in unit tests; assert the managed part we control.
        assertEquals("VEGA-STING", ApkStore.FOLDER)
    }
}
