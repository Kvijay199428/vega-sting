package com.vega.sting.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the channel-aware OTA logic: release listing, manifest parsing,
 * candidate filtering and selection. No network or Android framework is needed.
 */
class UpdateCheckerTest {

    private val pkg = "com.vega.sting"
    private val sha = "a".repeat(64)

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
    fun `missing minor and patch default to zero`() {
        assertFalse(UpdateChecker.isNewer("2", "2.0.0"))
        assertTrue(UpdateChecker.isNewer("2.0.1", "2"))
    }

    @Test
    fun `numeric segments are compared numerically not lexically`() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"))
    }

    @Test
    fun `invalid versions are never newer`() {
        assertFalse(UpdateChecker.isNewer("abc", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.1", "abc"))
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
        assertNull(UpdateChecker.normalizeVersion("release"))
    }

    // ---- parseReleases ----

    @Test
    fun `parseReleases reads tag, flags and assets`() {
        val json = """
            [
              {
                "tag_name": "debug-v2.0.2-dev.1",
                "prerelease": true,
                "draft": false,
                "body": "Dev notes",
                "published_at": "2026-10-01T10:00:00Z",
                "assets": [
                  { "name": "update.json", "browser_download_url": "https://ex/update.json", "size": 300 },
                  { "name": "vega-sting-debug-2.0.2-dev.1.apk", "browser_download_url": "https://ex/app.apk", "size": 19000000 }
                ]
              }
            ]
        """.trimIndent()

        val releases = UpdateChecker.parseReleases(json)
        assertEquals(1, releases?.size)
        val release = releases!!.first()
        assertEquals("debug-v2.0.2-dev.1", release.tagName)
        assertTrue(release.prerelease)
        assertFalse(release.draft)
        assertEquals("Dev notes", release.body)
        assertEquals(2, release.assets.size)
        assertEquals(19000000L, release.assets[1].size)
    }

    @Test
    fun `parseReleases tolerates json nulls and missing optionals`() {
        val json = """
            [
              { "tag_name": "v2.0.2", "body": null, "published_at": null, "assets": [] }
            ]
        """.trimIndent()
        val release = UpdateChecker.parseReleases(json)!!.first()
        assertNull(release.body)
        assertFalse(release.prerelease)
        assertFalse(release.draft)
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun `parseReleases returns null for malformed or non-array input`() {
        assertNull(UpdateChecker.parseReleases("{not json"))
        assertNull(UpdateChecker.parseReleases("""{"tag_name":"v1"}"""))
    }

    // ---- selectUpdate ----

    private fun manifest(
        channel: String = "DEBUG",
        versionName: String = "2.0.2-dev.1",
        versionCode: Long = 14,
        packageName: String = pkg,
        apkAsset: String = "vega-sting-debug-2.0.2-dev.1.apk",
        sha256: String = sha,
        minSupported: Long = 1,
        schema: Int = 1
    ): String = """
        {
          "schemaVersion": $schema,
          "channel": "$channel",
          "versionName": "$versionName",
          "versionCode": $versionCode,
          "packageName": "$packageName",
          "apkAsset": "$apkAsset",
          "sha256": "$sha256",
          "minSupportedVersionCode": $minSupported
        }
    """.trimIndent()

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: List<UpdateChecker.GitHubAsset>
    ) = UpdateChecker.GitHubRelease(tag, prerelease, draft, body = null, publishedAt = null, assets = assets)

    private fun manifestAsset(url: String) = UpdateChecker.GitHubAsset("update.json", url, 0)
    private fun apkAsset(name: String) = UpdateChecker.GitHubAsset(name, "https://ex/$name", 1000)

    private fun debugRelease(
        tag: String = "debug-v2.0.2-dev.1",
        prerelease: Boolean = true,
        draft: Boolean = false,
        apkName: String = "vega-sting-debug-2.0.2-dev.1.apk"
    ) = release(
        tag = tag,
        prerelease = prerelease,
        draft = draft,
        assets = listOf(manifestAsset("https://ex/$tag/manifest.json"), apkAsset(apkName))
    )

    private fun stableRelease(
        tag: String = "v2.0.2",
        prerelease: Boolean = false,
        draft: Boolean = false,
        apkName: String = "vega-sting-stable-2.0.2.apk"
    ) = release(
        tag = tag,
        prerelease = prerelease,
        draft = draft,
        assets = listOf(manifestAsset("https://ex/$tag/manifest.json"), apkAsset(apkName))
    )

    private fun stableManifest(
        versionName: String = "2.0.2",
        versionCode: Long = 13,
        apkName: String = "vega-sting-stable-2.0.2.apk"
    ) = manifest(
        channel = "STABLE",
        versionName = versionName,
        versionCode = versionCode,
        apkAsset = apkName
    )

    @Test
    fun `debug channel accepts a published prerelease`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease()),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 13,
            installedPackageName = pkg,
            fetchManifest = { manifest() }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Available)
        val update = (result as UpdateChecker.SelectionResult.Available).update
        assertEquals(UpdateChannel.DEBUG, update.channel)
        assertEquals(14L, update.versionCode)
        assertEquals("2.0.2-dev.1", update.versionName)
    }

    @Test
    fun `stable channel ignores prereleases`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease()),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 13,
            installedPackageName = pkg,
            fetchManifest = { manifest(channel = "STABLE") }
        )
        assertEquals(UpdateChecker.SelectionResult.UpToDate, result)
    }

    @Test
    fun `drafts are never considered`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease(draft = true)),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 13,
            installedPackageName = pkg,
            fetchManifest = { manifest() }
        )
        assertEquals(UpdateChecker.SelectionResult.UpToDate, result)
    }

    @Test
    fun `tag with the wrong channel prefix is ignored`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease(tag = "v2.0.3")),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 13,
            installedPackageName = pkg,
            fetchManifest = { manifest() }
        )
        assertEquals(UpdateChecker.SelectionResult.UpToDate, result)
    }

    @Test
    fun `stable channel selects a plain-v tagged release`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(stableRelease(tag = "v2.0.2")),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 12,
            installedPackageName = pkg,
            fetchManifest = { stableManifest() }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Available)
        val update = (result as UpdateChecker.SelectionResult.Available).update
        assertEquals(UpdateChannel.STABLE, update.channel)
        assertEquals("2.0.2", update.versionName)
        assertEquals(13L, update.versionCode)
    }

    @Test
    fun `stable channel also accepts the legacy stable-v tag prefix`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(stableRelease(tag = "stable-v2.0.2")),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 12,
            installedPackageName = pkg,
            fetchManifest = { stableManifest() }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Available)
        assertEquals(
            "2.0.2",
            (result as UpdateChecker.SelectionResult.Available).update.versionName
        )
    }

    @Test
    fun `migration contract - a plain stable tag stays parseable by the older updater`() {
        // The updater already installed on user devices only parses tags that
        // begin with a digit after an optional leading 'v'. Every STABLE tag
        // must satisfy this so those builds can still discover the release.
        assertEquals("2.0.2", UpdateChecker.normalizeVersion("v2.0.2"))
        assertTrue(UpdateChecker.isNewer("2.0.2", "2.0.1"))
    }

    @Test
    fun `manifest channel mismatch is rejected`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease()),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 13,
            installedPackageName = pkg,
            fetchManifest = { manifest(channel = "STABLE") }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Rejected)
    }

    @Test
    fun `package mismatch is rejected`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease()),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 13,
            installedPackageName = pkg,
            fetchManifest = { manifest(packageName = "com.evil.clone") }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Rejected)
    }

    @Test
    fun `asset name mismatch is rejected`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease()),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 13,
            installedPackageName = pkg,
            fetchManifest = { manifest(apkAsset = "not-attached.apk") }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Rejected)
    }

    @Test
    fun `invalid checksum in the manifest is rejected`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease()),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 13,
            installedPackageName = pkg,
            fetchManifest = { manifest(sha256 = "deadbeef") }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Rejected)
    }

    @Test
    fun `an older candidate is reported as up to date`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease()),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 13,
            installedPackageName = pkg,
            fetchManifest = { manifest(versionCode = 10) }
        )
        assertEquals(UpdateChecker.SelectionResult.UpToDate, result)
    }

    @Test
    fun `a candidate that requires a newer install is rejected`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease()),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 5,
            installedPackageName = pkg,
            fetchManifest = { manifest(minSupported = 20) }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Rejected)
    }

    @Test
    fun `a malformed manifest is skipped`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease()),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 13,
            installedPackageName = pkg,
            fetchManifest = { "{ this is not json" }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Rejected)
    }

    @Test
    fun `the highest compatible version code wins, not the newest release`() {
        val newest = debugRelease(
            tag = "debug-v2.0.4-dev.1",
            apkName = "vega-sting-debug-2.0.4-dev.1.apk"
        )
        val highest = debugRelease(
            tag = "debug-v2.0.3-dev.5",
            apkName = "vega-sting-debug-2.0.3-dev.5.apk"
        )
        val manifests = mapOf(
            "https://ex/debug-v2.0.4-dev.1/manifest.json" to manifest(versionName = "2.0.4-dev.1", versionCode = 15, apkAsset = "vega-sting-debug-2.0.4-dev.1.apk"),
            "https://ex/debug-v2.0.3-dev.5/manifest.json" to manifest(versionName = "2.0.3-dev.5", versionCode = 40, apkAsset = "vega-sting-debug-2.0.3-dev.5.apk")
        )
        val result = UpdateChecker.selectUpdate(
            releases = listOf(newest, highest),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 13,
            installedPackageName = pkg,
            fetchManifest = { url -> manifests[url] }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Available)
        assertEquals(40L, (result as UpdateChecker.SelectionResult.Available).update.versionCode)
    }

    // ---- channel switching ----

    @Test
    fun `switching channel with an equal version code is a policy violation`() {
        // Version codes are globally unique, so a DEBUG build sharing the
        // installed STABLE build's code cannot be a legitimate release. It must
        // be reported as a policy violation, never offered as an update.
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease()),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 14,
            installedPackageName = pkg,
            installedChannel = UpdateChannel.STABLE,
            installedVersionName = "2.0.2",
            fetchManifest = { manifest(versionName = "2.0.2-dev.1", versionCode = 14) }
        )
        assertTrue(result is UpdateChecker.SelectionResult.PolicyViolation)
    }

    @Test
    fun `a manual switch offers the target channel's newest build even when older`() {
        // Device on DEBUG 2.0.3-dev.1 (code 14) selecting STABLE, whose newest is
        // 2.0.2 (code 13). The switch is a deliberate reinstall, so a manual
        // check offers the channel's newest build even though Android may refuse
        // the downgrade (the UI warns about it).
        val result = UpdateChecker.selectUpdate(
            releases = listOf(stableRelease(tag = "v2.0.2")),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 14,
            installedPackageName = pkg,
            installedChannel = UpdateChannel.DEBUG,
            installedVersionName = "2.0.3-dev.1",
            allowSwitchDowngrade = true,
            fetchManifest = { stableManifest(versionName = "2.0.2", versionCode = 13) }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Available)
        val update = (result as UpdateChecker.SelectionResult.Available).update
        assertEquals(UpdateChannel.STABLE, update.channel)
        assertEquals("2.0.2", update.versionName)
        assertEquals(13L, update.versionCode)
    }

    @Test
    fun `an automatic switch to an older build stays quiet as up to date`() {
        // Without allowSwitchDowngrade (the throttled automatic check), an older
        // target build must not prompt: only manual checks offer a downgrade.
        val result = UpdateChecker.selectUpdate(
            releases = listOf(stableRelease(tag = "v2.0.2")),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 14,
            installedPackageName = pkg,
            installedChannel = UpdateChannel.DEBUG,
            installedVersionName = "2.0.3-dev.1",
            fetchManifest = { stableManifest(versionName = "2.0.2", versionCode = 13) }
        )
        assertEquals(UpdateChecker.SelectionResult.UpToDate, result)
    }

    @Test
    fun `a manual switch offers the channel's newest when several are older`() {
        val older = stableRelease(tag = "v2.0.1", apkName = "vega-sting-stable-2.0.1.apk")
        val newest = stableRelease(tag = "v2.0.2", apkName = "vega-sting-stable-2.0.2.apk")
        val manifests = mapOf(
            "https://ex/v2.0.1/manifest.json" to stableManifest(
                versionName = "2.0.1", versionCode = 12, apkName = "vega-sting-stable-2.0.1.apk"
            ),
            "https://ex/v2.0.2/manifest.json" to stableManifest(
                versionName = "2.0.2", versionCode = 13, apkName = "vega-sting-stable-2.0.2.apk"
            )
        )
        val result = UpdateChecker.selectUpdate(
            releases = listOf(older, newest),
            channel = UpdateChannel.STABLE,
            installedVersionCode = 15,
            installedPackageName = pkg,
            installedChannel = UpdateChannel.DEBUG,
            installedVersionName = "2.0.4-dev.1",
            allowSwitchDowngrade = true,
            fetchManifest = { url -> manifests[url] }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Available)
        assertEquals(13L, (result as UpdateChecker.SelectionResult.Available).update.versionCode)
    }

    @Test
    fun `switching to an empty channel reports channel-ahead, not up to date`() {
        val result = UpdateChecker.selectUpdate(
            releases = emptyList(),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 13,
            installedPackageName = pkg,
            installedChannel = UpdateChannel.STABLE,
            installedVersionName = "2.0.2",
            fetchManifest = { null }
        )
        assertTrue(result is UpdateChecker.SelectionResult.ChannelAhead)
    }

    @Test
    fun `same-channel equal code is up to date, not an offer`() {
        // Version codes are globally unique, so an equal code (even with a
        // "newer" versionName) is never a newer build: there is nothing to
        // install. The updater must say so instead of offering a build Android
        // would reject.
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease(tag = "debug-v2.0.4-dev.2", apkName = "vega-sting-debug-2.0.4-dev.2.apk")),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 15,
            installedPackageName = pkg,
            installedChannel = UpdateChannel.DEBUG,
            installedVersionName = "2.0.4-dev.1",
            fetchManifest = { manifest(versionName = "2.0.4-dev.2", versionCode = 15, apkAsset = "vega-sting-debug-2.0.4-dev.2.apk") }
        )
        assertEquals(UpdateChecker.SelectionResult.UpToDate, result)
    }

    @Test
    fun `same-channel strictly higher code is offered`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease(tag = "debug-v2.0.4-dev.2", apkName = "vega-sting-debug-2.0.4-dev.2.apk")),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 15,
            installedPackageName = pkg,
            installedChannel = UpdateChannel.DEBUG,
            installedVersionName = "2.0.4-dev.1",
            fetchManifest = { manifest(versionName = "2.0.4-dev.2", versionCode = 16, apkAsset = "vega-sting-debug-2.0.4-dev.2.apk") }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Available)
        assertEquals(16L, (result as UpdateChecker.SelectionResult.Available).update.versionCode)
    }

    @Test
    fun `channel switch with a strictly higher code is an ordinary update`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease()),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 14,
            installedPackageName = pkg,
            installedChannel = UpdateChannel.STABLE,
            installedVersionName = "2.0.2",
            fetchManifest = { manifest(versionName = "2.0.2-dev.1", versionCode = 15) }
        )
        assertTrue(result is UpdateChecker.SelectionResult.Available)
        assertEquals(15L, (result as UpdateChecker.SelectionResult.Available).update.versionCode)
    }

    @Test
    fun `same-channel equal code with the same dev build is up to date`() {
        val result = UpdateChecker.selectUpdate(
            releases = listOf(debugRelease(tag = "debug-v2.0.4-dev.1", apkName = "vega-sting-debug-2.0.4-dev.1.apk")),
            channel = UpdateChannel.DEBUG,
            installedVersionCode = 15,
            installedPackageName = pkg,
            installedChannel = UpdateChannel.DEBUG,
            installedVersionName = "2.0.4-dev.1",
            fetchManifest = { manifest(versionName = "2.0.4-dev.1", versionCode = 15, apkAsset = "vega-sting-debug-2.0.4-dev.1.apk") }
        )
        assertEquals(UpdateChecker.SelectionResult.UpToDate, result)
    }

    // ---- compareVersions ----

    @Test
    fun `compareVersions orders numeric bases`() {
        assertTrue(UpdateChecker.compareVersions("2.0.3", "2.0.2") > 0)
        assertTrue(UpdateChecker.compareVersions("2.0.2", "2.0.3") < 0)
        assertEquals(0, UpdateChecker.compareVersions("2.0.3", "2.0.3"))
        assertTrue(UpdateChecker.compareVersions("2.0.4-dev.1", "2.0.3") > 0)
    }

    @Test
    fun `compareVersions orders dev build numbers`() {
        assertTrue(UpdateChecker.compareVersions("2.0.4-dev.2", "2.0.4-dev.1") > 0)
        assertTrue(UpdateChecker.compareVersions("2.0.4-dev.1", "2.0.4-dev.2") < 0)
        assertEquals(0, UpdateChecker.compareVersions("2.0.4-dev.5", "2.0.4-dev.5"))
    }

    @Test
    fun `compareVersions ranks a stable name above its dev builds`() {
        assertTrue(UpdateChecker.compareVersions("2.0.3", "2.0.3-dev.5") > 0)
        assertTrue(UpdateChecker.compareVersions("2.0.3-dev.5", "2.0.3") < 0)
    }

    @Test
    fun `compareVersions treats a blank reference as older`() {
        assertTrue(UpdateChecker.compareVersions("2.0.3", "") > 0)
        assertTrue(UpdateChecker.compareVersions("2.0.3", null) > 0)
    }

    // ---- ReleaseManifest ----

    @Test
    fun `release manifest parses a valid body`() {
        val parsed = ReleaseManifest.parse(manifest())
        assertEquals(UpdateChannel.DEBUG, parsed?.channel)
        assertEquals("2.0.2-dev.1", parsed?.versionName)
        assertEquals(14L, parsed?.versionCode)
        assertEquals(pkg, parsed?.packageName)
        assertTrue(parsed!!.hasValidSha256())
    }

    @Test
    fun `release manifest rejects unsupported schema versions`() {
        assertNull(ReleaseManifest.parse(manifest(schema = 2)))
    }

    @Test
    fun `release manifest rejects unknown channels`() {
        assertNull(ReleaseManifest.parse(manifest(channel = "BETA")))
    }

    @Test
    fun `release manifest rejects blank or missing fields`() {
        assertNull(ReleaseManifest.parse("""{"schemaVersion":1,"channel":"DEBUG"}"""))
        assertNull(ReleaseManifest.parse("not json"))
    }

    @Test
    fun `release manifest detects a malformed checksum`() {
        assertFalse(ReleaseManifest.parse(manifest(sha256 = "abc"))!!.hasValidSha256())
    }

    @Test
    fun `release manifest parses the optional apkSizeBytes`() {
        val withSize = """
            {
              "schemaVersion": 1,
              "channel": "STABLE",
              "versionName": "2.0.5",
              "versionCode": 17,
              "packageName": "$pkg",
              "apkAsset": "vega-sting-stable-2.0.5.apk",
              "apkSizeBytes": 34567890,
              "sha256": "$sha",
              "minSupportedVersionCode": 1
            }
        """.trimIndent()
        assertEquals(34567890L, ReleaseManifest.parse(withSize)?.apkSizeBytes)
    }

    @Test
    fun `release manifest defaults apkSizeBytes to zero when absent`() {
        // Manifests published before the field existed must keep parsing.
        assertEquals(0L, ReleaseManifest.parse(manifest())?.apkSizeBytes)
    }

    @Test
    fun `update info prefers the manifest size and falls back to the asset size`() {
        val fromManifest = UpdateChecker.UpdateInfo(
            channel = UpdateChannel.STABLE,
            versionName = "2.0.5",
            versionCode = 17,
            apkUrl = "https://ex/app.apk",
            assetName = "vega-sting-stable-2.0.5.apk",
            sha256 = sha,
            notes = null,
            assetSizeBytes = 1000L,
            manifestSizeBytes = 2000L
        )
        assertEquals(2000L, fromManifest.expectedSizeBytes)

        val assetOnly = fromManifest.copy(manifestSizeBytes = 0L)
        assertEquals(1000L, assetOnly.expectedSizeBytes)
    }
}
