package com.vega.sting.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the OTA version-comparison and release-parsing logic.
 * These are the parts most likely to silently break OTA updates, so they are
 * covered without any network or Android framework dependency.
 */
class UpdateCheckerTest {

    // ---- isNewer ----

    @Test
    fun `newer patch version is detected`() {
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0.0"))
    }

    @Test
    fun `newer minor version is detected`() {
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.0.9"))
    }

    @Test
    fun `newer major version is detected`() {
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `identical version is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.0.1", "1.0.1"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun `missing minor and patch default to zero`() {
        // "2" == "2.0.0"
        assertFalse(UpdateChecker.isNewer("2", "2.0.0"))
        assertTrue(UpdateChecker.isNewer("2.0.1", "2"))
        assertTrue(UpdateChecker.isNewer("3", "2.9.9"))
    }

    @Test
    fun `v prefix is ignored on both sides`() {
        assertTrue(UpdateChecker.isNewer("v1.1.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "v1.0.0"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertTrue(UpdateChecker.isNewer("  1.0.1  ", "1.0.0"))
    }

    @Test
    fun `numeric segments are compared numerically not lexically`() {
        // The bug this guards: "10" < "9" as strings, but 10 > 9 as numbers.
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"))
    }

    @Test
    fun `invalid remote version is not newer`() {
        assertFalse(UpdateChecker.isNewer("abc", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("", "1.0.0"))
    }

    @Test
    fun `invalid local version is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.0.1", "abc"))
        assertFalse(UpdateChecker.isNewer("1.0.1", ""))
    }

    @Test
    fun `non-numeric suffix is ignored after numeric prefix`() {
        // "-rc1" is not numeric, so only "1.0.1" is considered.
        assertTrue(UpdateChecker.isNewer("1.0.1-rc1", "1.0.0"))
    }

    // ---- normalizeVersion ----

    @Test
    fun `normalizeVersion strips v prefix and keeps numeric segments`() {
        assertEquals("1.0.1", UpdateChecker.normalizeVersion("v1.0.1"))
        assertEquals("1.0", UpdateChecker.normalizeVersion("1.0"))
        assertEquals("2", UpdateChecker.normalizeVersion("v2"))
    }

    @Test
    fun `normalizeVersion returns null when no numeric segment exists`() {
        assertNull(UpdateChecker.normalizeVersion("v"))
        assertNull(UpdateChecker.normalizeVersion(""))
        assertNull(UpdateChecker.normalizeVersion("   "))
        assertNull(UpdateChecker.normalizeVersion("release"))
    }

    // ---- parseRelease ----

    private val validRelease = """
        {
          "tag_name": "v1.0.1",
          "body": "Fixes the overlay flicker.",
          "published_at": "2026-09-29T10:00:00Z",
          "assets": [
            {
              "name": "notes.txt",
              "browser_download_url": "https://example.com/notes.txt"
            },
            {
              "name": "VEGA-STING-1.0.1-release.apk",
              "browser_download_url": "https://example.com/app.apk",
              "size": 19456000
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parseRelease picks the apk asset and ignores non-apk assets`() {
        val info = UpdateChecker.parseRelease(validRelease)
        assertEquals("1.0.1", info?.versionName)
        assertEquals("VEGA-STING-1.0.1-release.apk", info?.assetName)
        assertEquals("https://example.com/app.apk", info?.apkUrl)
        assertEquals(19456000L, info?.assetSizeBytes)
    }

    @Test
    fun `parseRelease reads release notes and published date`() {
        val info = UpdateChecker.parseRelease(validRelease)
        assertEquals("Fixes the overlay flicker.", info?.notes)
        assertEquals("2026-09-29T10:00:00Z", info?.publishedAt)
    }

    @Test
    fun `parseRelease returns null when no apk asset exists`() {
        val json = """
            {
              "tag_name": "v1.0.1",
              "body": "docs only",
              "assets": [
                { "name": "notes.txt", "browser_download_url": "https://example.com/n.txt" }
              ]
            }
        """.trimIndent()
        assertNull(UpdateChecker.parseRelease(json))
    }

    @Test
    fun `parseRelease returns null for an empty asset list`() {
        val json = """{ "tag_name": "v1.0.1", "body": "", "assets": [] }"""
        assertNull(UpdateChecker.parseRelease(json))
    }

    @Test
    fun `parseRelease returns null when tag is missing`() {
        val json = """{ "body": "x", "assets": [ { "name": "a.apk", "browser_download_url": "u" } ] }"""
        assertNull(UpdateChecker.parseRelease(json))
    }

    @Test
    fun `parseRelease returns null for malformed json`() {
        assertNull(UpdateChecker.parseRelease("{not json"))
    }

    @Test
    fun `parseRelease tolerates a missing size field`() {
        val json = """
            {
              "tag_name": "v2.0.0",
              "assets": [ { "name": "a.apk", "browser_download_url": "https://example.com/a.apk" } ]
            }
        """.trimIndent()
        val info = UpdateChecker.parseRelease(json)
        assertEquals("2.0.0", info?.versionName)
        assertEquals(0L, info?.assetSizeBytes)
    }

    @Test
    fun `parseRelease tolerates explicit json nulls from the GitHub api`() {
        val json = """
            {
              "tag_name": "v1.2.0",
              "body": null,
              "published_at": null,
              "assets": [
                { "name": "a.apk", "browser_download_url": "https://example.com/a.apk", "size": null }
              ]
            }
        """.trimIndent()
        val info = UpdateChecker.parseRelease(json)
        assertEquals("1.2.0", info?.versionName)
        assertNull(info?.notes)
        assertNull(info?.publishedAt)
        assertEquals(0L, info?.assetSizeBytes)
    }

    @Test
    fun `parseRelease ignores an apk asset with no download url`() {
        val json = """
            {
              "tag_name": "v1.0.1",
              "assets": [ { "name": "broken.apk" } ]
            }
        """.trimIndent()
        assertNull(UpdateChecker.parseRelease(json))
    }

    @Test
    fun `parseRelease treats blank body as null notes`() {
        val json = """
            {
              "tag_name": "v1.1.0",
              "body": "   ",
              "assets": [ { "name": "a.apk", "browser_download_url": "https://example.com/a.apk" } ]
            }
        """.trimIndent()
        assertNull(UpdateChecker.parseRelease(json)?.notes)
    }
}
