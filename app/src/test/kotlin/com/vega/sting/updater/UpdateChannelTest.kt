package com.vega.sting.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelTest {

    @Test
    fun `fromRaw recognises channel names case-insensitively`() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromRaw("stable"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromRaw("STABLE"))
        assertEquals(UpdateChannel.DEBUG, UpdateChannel.fromRaw(" debug "))
        assertEquals(UpdateChannel.DEBUG, UpdateChannel.fromRaw("Debug"))
    }

    @Test
    fun `fromRaw rejects unknown or missing values`() {
        assertNull(UpdateChannel.fromRaw(null))
        assertNull(UpdateChannel.fromRaw(""))
        assertNull(UpdateChannel.fromRaw("beta"))
    }

    @Test
    fun `fromPreference falls back to the installed channel`() {
        assertEquals(UpdateChannel.installed(), UpdateChannel.fromPreference(null))
        assertEquals(UpdateChannel.installed(), UpdateChannel.fromPreference("nonsense"))
    }

    @Test
    fun `fromPreference honours an explicit saved value`() {
        assertEquals(UpdateChannel.DEBUG, UpdateChannel.fromPreference("DEBUG"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromPreference("STABLE"))
    }

    @Test
    fun `stable accepts the plain and legacy tag prefixes`() {
        val stable = UpdateChannel.STABLE
        assertTrue(stable.matchesTag("v2.0.2"))
        assertTrue(stable.matchesTag("stable-v2.0.2"))
        assertFalse(stable.matchesTag("debug-v2.0.3-dev.1"))
        assertEquals("2.0.2", stable.versionFromTag("v2.0.2"))
        assertEquals("2.0.2", stable.versionFromTag("stable-v2.0.2"))
        assertNull(stable.versionFromTag("debug-v2.0.3-dev.1"))
    }

    @Test
    fun `debug accepts only its own tag prefix`() {
        val debug = UpdateChannel.DEBUG
        assertTrue(debug.matchesTag("debug-v2.0.3-dev.1"))
        assertFalse(debug.matchesTag("v2.0.3"))
        assertFalse(debug.matchesTag("stable-v2.0.3"))
        assertEquals("2.0.3-dev.1", debug.versionFromTag("debug-v2.0.3-dev.1"))
        assertNull(debug.versionFromTag("v2.0.3"))
    }
}
