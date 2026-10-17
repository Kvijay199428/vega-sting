package com.vega.sting.updater

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves the newest installable release for a selected [UpdateChannel].
 *
 * The updater never trusts `/releases/latest` (it only ever returns the newest
 * published *stable* release). Instead it lists releases, filters them by
 * channel, and requires a machine-readable `update.json` manifest so a release
 * can be matched to its channel, package, version and checksum deterministically.
 *
 * Only the pure parsing/selection functions talk to plain Strings, so they are
 * unit-testable without a network or the Android framework; [fetchUpdate] is the
 * thin HTTP wrapper around them.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Name of the machine-readable manifest attached to every release. */
    const val MANIFEST_ASSET_NAME = "update.json"

    private const val RELEASES_PER_PAGE = 50

    /**
     * A resolved, installable update for the selected channel.
     *
     * @param channel the channel the release belongs to
     * @param versionName the version name from the release manifest/tag
     * @param versionCode the Android version code from the release manifest
     * @param apkUrl direct download URL of the APK asset
     * @param assetName name of the APK asset
     * @param sha256 expected SHA-256 of the APK (lowercase hex)
     * @param notes release notes body
     * @param assetSizeBytes APK size reported by GitHub (0 when unknown)
     * @param manifestSizeBytes APK size declared by the release manifest (0 when absent)
     */
    data class UpdateInfo(
        val channel: UpdateChannel,
        val versionName: String,
        val versionCode: Long,
        val apkUrl: String,
        val assetName: String,
        val sha256: String,
        val notes: String?,
        val publishedAt: String? = null,
        val assetSizeBytes: Long = 0L,
        val manifestSizeBytes: Long = 0L
    ) {
        /**
         * Best-known APK size for the storage preflight: the manifest value when
         * present, otherwise the size GitHub reports for the asset.
         */
        val expectedSizeBytes: Long get() = if (manifestSizeBytes > 0L) manifestSizeBytes else assetSizeBytes
    }

    /** Outcome of resolving a release for a channel. */
    sealed interface SelectionResult {
        /** A newer, installable release was found. */
        data class Available(val update: UpdateInfo) : SelectionResult

        /** No newer release exists for this channel. */
        data object UpToDate : SelectionResult

        /**
         * The selected channel has published no release the updater can use yet
         * (for example a freshly created channel). [reason] explains that and
         * confirms the preference is kept until one is published. Distinct from
         * [Rejected], which means releases exist but none can replace this
         * installation.
         */
        data class ChannelAhead(val reason: String) : SelectionResult

        /**
         * Releases exist for this channel but none can be installed (wrong
         * channel/package, bad checksum, downgrade, …). [reason] is shown to
         * the user instead of silently falling back to the other channel.
         */
        data class Rejected(val reason: String) : SelectionResult

        /** The check itself could not be completed (network/API failure). */
        data class Failed(val reason: String) : SelectionResult
    }

    // ------------------------------------------------------------------
    // Network
    // ------------------------------------------------------------------

    /**
     * Lists releases and resolves the best candidate for [channel].
     *
     * @param installedVersionCode the installed APK's version code
     * @param installedPackageName the installed package id
     * @param installedChannel the channel the installed build was published on
     * @param installedVersionName the installed build's version name
     * @param allowSwitchDowngrade whether a channel switch may offer a release
     *   whose version code is lower than the installed build (manual checks only)
     */
    fun fetchUpdate(
        owner: String,
        repo: String,
        channel: UpdateChannel,
        installedVersionCode: Long,
        installedPackageName: String,
        installedChannel: UpdateChannel,
        installedVersionName: String,
        allowSwitchDowngrade: Boolean = false
    ): SelectionResult {
        val endpoint = "https://api.github.com/repos/$owner/$repo/releases?per_page=$RELEASES_PER_PAGE"
        val body = httpGetString(endpoint, "application/vnd.github+json")
            ?: return SelectionResult.Failed("Could not reach the GitHub Releases API")

        val releases = parseReleases(body)
            ?: return SelectionResult.Failed("GitHub returned an unexpected response")

        return selectUpdate(
            releases = releases,
            channel = channel,
            installedVersionCode = installedVersionCode,
            installedPackageName = installedPackageName,
            fetchManifest = { url -> httpGetString(url, "application/octet-stream") },
            installedChannel = installedChannel,
            installedVersionName = installedVersionName,
            allowSwitchDowngrade = allowSwitchDowngrade
        )
    }

    private fun httpGetString(url: String, accept: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // GitHub requires a User-Agent on all API requests.
                setRequestProperty("User-Agent", "VEGA-STING-Updater")
                setRequestProperty("Accept", accept)
                instanceFollowRedirects = true
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                android.util.Log.w(TAG, "HTTP ${connection.responseCode} for $url")
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Request failed for $url: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // Pure parsing / selection
    // ------------------------------------------------------------------

    /** A GitHub release, reduced to the fields the updater uses. */
    data class GitHubAsset(val name: String, val url: String, val size: Long)

    data class GitHubRelease(
        val tagName: String,
        val prerelease: Boolean,
        val draft: Boolean,
        val body: String?,
        val publishedAt: String?,
        val assets: List<GitHubAsset>
    )

    /** Parses a `/releases` array body. Returns null for malformed input. */
    internal fun parseReleases(json: String): List<GitHubRelease>? {
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonArray) return null
            root.asJsonArray.mapNotNull { element ->
                (element as? JsonObject)?.let { parseReleaseObject(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseReleaseObject(root: JsonObject): GitHubRelease? {
        val tag = root.get("tag_name").asStringOrNull() ?: return null
        val assets = root.getAsJsonArray("assets")?.mapNotNull { element ->
            (element as? JsonObject)?.let { asset ->
                val name = asset.get("name").asStringOrNull() ?: return@let null
                val url = asset.get("browser_download_url").asStringOrNull() ?: return@let null
                GitHubAsset(name, url, asset.get("size").asLongOrNull() ?: 0L)
            }
        }.orEmpty()

        return GitHubRelease(
            tagName = tag,
            prerelease = root.get("prerelease").asBooleanOrNull() ?: false,
            draft = root.get("draft").asBooleanOrNull() ?: false,
            body = root.get("body").asStringOrNull()?.takeIf { it.isNotBlank() },
            publishedAt = root.get("published_at").asStringOrNull(),
            assets = assets
        )
    }

    /**
     * Selects the highest compatible release for [channel].
     *
     * A *channel switch* (the selected channel differs from the channel the
     * installed build came from) offers that channel's newest valid release.
     * It may install an **equal** version code -- Android permits an equal-code
     * reinstall but never a lower one -- and, when [allowSwitchDowngrade] is set
     * (a manual check), even a lower one: the switch is a deliberate reinstall
     * and Android's refusal of a true downgrade is warned about before the
     * download and reported after the attempt. A same-channel update still has
     * to be a real bump: a strictly higher version code, or an equal code with a
     * newer version name (the `x.y.z-dev.N` line shares the stable's version
     * code).
     *
     * @param installedChannel the channel the installed build was published on
     *   (defaults to the selected channel, i.e. no switch)
     * @param installedVersionName the installed build's version name, used to
     *   order equal version codes within the same channel
     * @param allowSwitchDowngrade whether a channel switch may offer a release
     *   whose version code is lower than the installed build (manual checks only,
     *   so the automatic check stays quiet on a downgrade)
     * @param fetchManifest downloads an asset URL to its text body (injected so
     *   this function stays pure and testable)
     */
    internal fun selectUpdate(
        releases: List<GitHubRelease>,
        channel: UpdateChannel,
        installedVersionCode: Long,
        installedPackageName: String,
        fetchManifest: (String) -> String?,
        installedChannel: UpdateChannel = channel,
        installedVersionName: String = "",
        allowSwitchDowngrade: Boolean = false
    ): SelectionResult {
        val switchingChannel = channel != installedChannel

        val candidates = releases.filter { release ->
            !release.draft &&
                channel.matchesTag(release.tagName) &&
                // STABLE must never surface a pre-release; DEBUG may.
                (channel == UpdateChannel.DEBUG || !release.prerelease)
        }

        if (candidates.isEmpty()) {
            // Switching to a channel that has published nothing yet is not an
            // "up to date" result -- say so, rather than implying the user is
            // already on that channel's newest build.
            return if (switchingChannel) {
                SelectionResult.ChannelAhead(
                    "No ${channel.label} release is available yet. Your update channel is set to " +
                        "${channel.label}; you'll be notified when one is published."
                )
            } else {
                SelectionResult.UpToDate
            }
        }

        var best: UpdateInfo? = null
        var highestSeenCode = Long.MIN_VALUE
        var highestSeenName: String? = null
        val reasons = mutableListOf<String>()

        for (release in candidates) {
            val manifestAsset = release.assets.firstOrNull {
                it.name.equals(MANIFEST_ASSET_NAME, ignoreCase = true)
            }
            if (manifestAsset == null) {
                reasons += "${release.tagName}: missing $MANIFEST_ASSET_NAME"
                continue
            }

            val manifestJson = fetchManifest(manifestAsset.url)
            if (manifestJson == null) {
                reasons += "${release.tagName}: could not download the manifest"
                continue
            }

            val manifest = ReleaseManifest.parse(manifestJson)
            if (manifest == null) {
                reasons += "${release.tagName}: malformed manifest"
                continue
            }

            if (manifest.channel != channel) {
                reasons += "${release.tagName}: manifest channel ${manifest.channel.label} does not match ${channel.label}"
                continue
            }

            val expectedVersion = channel.versionFromTag(release.tagName)
            if (expectedVersion == null) {
                reasons += "${release.tagName}: unrecognised channel tag prefix"
                continue
            }
            if (manifest.versionName != expectedVersion) {
                reasons += "${release.tagName}: manifest version ${manifest.versionName} does not match the tag"
                continue
            }

            if (manifest.packageName != installedPackageName) {
                reasons += "${release.tagName}: package ${manifest.packageName} does not match $installedPackageName"
                continue
            }

            if (!manifest.hasValidSha256()) {
                reasons += "${release.tagName}: invalid SHA-256 in the manifest"
                continue
            }

            val apkAsset = release.assets.firstOrNull { it.name == manifest.apkAsset }
            if (apkAsset == null) {
                reasons += "${release.tagName}: APK asset ${manifest.apkAsset} is not attached"
                continue
            }

            if (installedVersionCode < manifest.minSupportedVersionCode) {
                reasons += "${release.tagName}: requires at least version code ${manifest.minSupportedVersionCode}"
                continue
            }

            // Track the newest release this install could actually take, by
            // version code, breaking ties with the version name so a
            // `x.y.z-dev.2` outranks a `x.y.z-dev.1` sharing the same code.
            if (manifest.versionCode > highestSeenCode ||
                (manifest.versionCode == highestSeenCode &&
                    compareVersions(manifest.versionName, highestSeenName) > 0)
            ) {
                highestSeenCode = manifest.versionCode
                highestSeenName = manifest.versionName
            }

            val installable =
                if (switchingChannel) {
                    // A switch is a deliberate reinstall: it may install the same
                    // code, and -- for a manual check -- even a lower one. Android
                    // refuses a true downgrade, which the UI warns about before
                    // the download and reports after the install attempt.
                    allowSwitchDowngrade || manifest.versionCode >= installedVersionCode
                } else {
                    manifest.versionCode > installedVersionCode ||
                        (manifest.versionCode == installedVersionCode &&
                            installedVersionName.isNotBlank() &&
                            compareVersions(manifest.versionName, installedVersionName) > 0)
                }
            if (!installable) {
                reasons += "${release.tagName}: version code ${manifest.versionCode} is not newer than $installedVersionCode"
                continue
            }

            val current = best
            val better = current == null ||
                manifest.versionCode > current.versionCode ||
                (manifest.versionCode == current.versionCode &&
                    compareVersions(manifest.versionName, current.versionName) > 0)
            if (better) {
                best = UpdateInfo(
                    channel = channel,
                    versionName = manifest.versionName,
                    versionCode = manifest.versionCode,
                    apkUrl = apkAsset.url,
                    assetName = apkAsset.name,
                    sha256 = manifest.sha256.lowercase(),
                    notes = release.body,
                    publishedAt = release.publishedAt,
                    assetSizeBytes = apkAsset.size,
                    manifestSizeBytes = manifest.apkSizeBytes
                )
            }
        }

        best?.let { return SelectionResult.Available(it) }

        // Nothing installable. When the newest release this install could take is
        // not newer than what's running, the ordinary answer is "up to date".
        // This also keeps the automatic check quiet after a channel switch whose
        // target channel only has older builds: a downgrade is offered on manual
        // checks only (see [allowSwitchDowngrade]), so the switch prompt comes
        // from the check the settings screen triggers, not from a periodic one.
        if (highestSeenCode != Long.MIN_VALUE && highestSeenCode <= installedVersionCode) {
            return SelectionResult.UpToDate
        }
        return SelectionResult.Rejected(reasons.firstOrNull() ?: "No compatible release found")
    }

    /**
     * Orders two channel version names, understanding the `x.y.z` and
     * `x.y.z-dev.N` forms. Within a single channel this lets an equal version
     * code still be recognised as newer (e.g. `2.0.4-dev.2` over `2.0.4-dev.1`,
     * which now share a version code). A plain stable name outranks the
     * `-dev` builds of the same numeric version.
     *
     * @return negative when [a] is older, positive when newer, 0 when equal
     */
    internal fun compareVersions(a: String, b: String?): Int {
        if (b.isNullOrBlank()) return if (a.isBlank()) 0 else 1
        val (aBase, aDev) = splitVersion(a)
        val (bBase, bDev) = splitVersion(b)
        val base = compareNumeric(aBase, bBase)
        if (base != 0) return base
        return aDev.compareTo(bDev)
    }

    private fun splitVersion(version: String): Pair<List<Int>, Int> {
        val cleaned = version.trim()
        val devMarker = "-dev."
        val devIndex = cleaned.indexOf(devMarker)
        val basePart = if (devIndex >= 0) cleaned.substring(0, devIndex) else cleaned.substringBefore('-')
        val numbers = basePart.removePrefix("v").split('.').map { segment ->
            segment.takeWhile(Char::isDigit).toIntOrNull() ?: 0
        }
        // A plain (stable) name outranks every -dev build of the same version.
        val devNumber = if (devIndex >= 0) {
            cleaned.substring(devIndex + devMarker.length).takeWhile(Char::isDigit).toIntOrNull() ?: 0
        } else {
            Int.MAX_VALUE
        }
        return numbers to devNumber
    }

    private fun compareNumeric(a: List<Int>, b: List<Int>): Int {
        val length = maxOf(a.size, b.size)
        for (i in 0 until length) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    /**
     * Normalizes a GitHub tag (e.g. "v1.0.1", "1.0", "1.0.1-rc1") into a dotted
     * numeric version. A leading "v" and any non-numeric suffix on a segment are
     * dropped. Returns null if no numeric version can be extracted.
     */
    internal fun normalizeVersion(raw: String): String? {
        val cleaned = raw.trim().removePrefix("v")
        if (cleaned.isEmpty()) return null
        val segments = cleaned.split(".")
        val numbers = segments
            .map { it.takeWhile(Char::isDigit) }
            .takeWhile { it.isNotEmpty() }
        if (numbers.isEmpty()) return null
        return numbers.joinToString(".")
    }

    /**
     * Compares two semantic versions numerically (major.minor.patch) and returns
     * true when [remote] is strictly newer than [local]. Retained for display
     * and diagnostics; candidate ordering itself uses the Android version code.
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parseSegments(remote) ?: return false
        val l = parseSegments(local) ?: return false
        val len = maxOf(r.size, l.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun parseSegments(version: String): List<Int>? {
        val cleaned = normalizeVersion(version) ?: return null
        return cleaned.split(".").map { seg -> seg.toIntOrNull() ?: return null }
    }

    private fun com.google.gson.JsonElement?.asStringOrNull(): String? {
        if (this == null || isJsonNull || !isJsonPrimitive) return null
        return asString
    }

    private fun com.google.gson.JsonElement?.asLongOrNull(): Long? {
        if (this == null || isJsonNull || !isJsonPrimitive) return null
        return runCatching { asLong }.getOrNull()
    }

    private fun com.google.gson.JsonElement?.asBooleanOrNull(): Boolean? {
        if (this == null || isJsonNull || !isJsonPrimitive) return null
        return runCatching { asBoolean }.getOrNull()
    }
}
