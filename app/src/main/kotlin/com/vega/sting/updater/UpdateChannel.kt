package com.vega.sting.updater

import com.vega.sting.BuildConfig

/**
 * The two OTA update channels VEGA STING publishes.
 *
 * The channel determines which GitHub releases the updater looks at. It does
 * NOT override Android's package-signature or version-code rules -- the
 * selected APK must still carry the same package id, the same signing
 * certificate and a **strictly higher** version code before it can be
 * installed (version codes are globally unique, so an equal code is never a
 * newer build).
 *
 * @param label the value written into the release `update.json` manifest and
 *   into the APK's `DEFAULT_UPDATE_CHANNEL` manifest meta-data
 * @param tagPrefixes the accepted GitHub release tag prefixes, longest first.
 *   STABLE keeps a plain `v…` prefix (see below) so that builds already
 *   installed on user devices -- whose updater cannot be changed -- can still
 *   discover new releases via `GET /releases/latest`; the legacy `stable-v…`
 *   form is accepted too.
 * @param apkPrefix the APK asset filename prefix for the channel
 */
enum class UpdateChannel(
    val label: String,
    val tagPrefixes: List<String>,
    val apkPrefix: String
) {
    // STABLE tags are the plain `v<versionName>` form (e.g. `v2.0.2`). The
    // legacy `stable-v<versionName>` form is still accepted. The plain form is
    // mandatory: the updater shipped in older installs only parses tags that
    // begin with a digit after an optional leading `v`, so a `stable-v…` tag
    // would be invisible to every already-installed build.
    STABLE("STABLE", listOf("stable-v", "v"), "vega-sting-stable-"),
    DEBUG("DEBUG", listOf("debug-v"), "vega-sting-debug-");

    /** True when [tag] is a release tag for this channel. */
    fun matchesTag(tag: String): Boolean = tagPrefixes.any { tag.startsWith(it) }

    /**
     * The version name encoded in [tag] (the tag with its channel prefix
     * removed), or null when the tag does not belong to this channel.
     */
    fun versionFromTag(tag: String): String? =
        tagPrefixes.firstOrNull { tag.startsWith(it) }?.let { tag.removePrefix(it) }

    companion object {
        /** Parses a stored/manifest channel value; null when unrecognised. */
        fun fromRaw(raw: String?): UpdateChannel? {
            val value = raw?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == value }
        }

        /** The channel of the currently installed APK, baked in at build time. */
        fun installed(): UpdateChannel =
            fromRaw(BuildConfig.DEFAULT_UPDATE_CHANNEL) ?: STABLE

        /** The user's saved preference, falling back to the installed channel. */
        fun fromPreference(raw: String?): UpdateChannel = fromRaw(raw) ?: installed()
    }
}
