package com.vega.sting.updater

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the latest VEGA STING release info from the public GitHub Releases API
 * and compares versions using semantic-version (x.y.z) ordering.
 *
 * The repository coordinates come from `BuildConfig.GITHUB_OWNER` /
 * `BuildConfig.GITHUB_REPO` so the updater always tracks the repo the app was
 * actually released from, rather than a copy-pasted constant that can drift.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /**
     * Metadata about an available update pulled from a GitHub release.
     *
     * @param versionName the semantic version parsed from the release tag (e.g. "1.0.1")
     * @param apkUrl download URL of the first .apk asset
     * @param assetName name of the .apk asset
     * @param notes short release description (may be null/empty)
     * @param publishedAt optional release timestamp from the API, used for display
     */
    data class UpdateInfo(
        val versionName: String,
        val apkUrl: String,
        val assetName: String,
        val notes: String?,
        val publishedAt: String? = null,
        /** Asset size in bytes as reported by GitHub; 0 when unknown. */
        val assetSizeBytes: Long = 0L
    )

    /**
     * Returns the latest release's update info, or null if the request fails,
     * times out, the release has no APK asset, or parsing fails.
     *
     * @param owner GitHub account/organisation that owns the repo
     * @param repo repository name
     */
    fun fetchLatestRelease(owner: String, repo: String): UpdateInfo? {
        var connection: HttpURLConnection? = null
        return try {
            val endpoint = "https://api.github.com/repos/$owner/$repo/releases/latest"
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // GitHub requires a User-Agent on all API requests.
                setRequestProperty("User-Agent", "VEGA-STING-Updater")
                setRequestProperty("Accept", "application/vnd.github+json")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                android.util.Log.w(TAG, "Releases API returned HTTP ${connection.responseCode}")
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseRelease(body)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to fetch latest release: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Parses a GitHub release JSON body into [UpdateInfo], picking the first
     * asset whose name looks like an APK.
     *
     * Pure function: no framework or network access, so it is directly unit-testable.
     * Returns null for malformed input rather than throwing.
     */
    internal fun parseRelease(json: String): UpdateInfo? {
        return try {
            val root = JsonParser.parseString(json).asJsonObject

            val tagName = root.get("tag_name").asStringOrNull() ?: return null
            val versionName = normalizeVersion(tagName) ?: return null

            val assets = root.getAsJsonArray("assets") ?: return null
            val apk = assets.firstOrNull { asset ->
                val name = (asset as? JsonObject)?.get("name").asStringOrNull().orEmpty()
                name.endsWith(".apk", ignoreCase = true)
            } as? JsonObject ?: return null

            val assetName = apk.get("name").asStringOrNull() ?: return null
            val apkUrl = apk.get("browser_download_url").asStringOrNull() ?: return null
            val notes = root.get("body").asStringOrNull()
            val publishedAt = root.get("published_at").asStringOrNull()
            val size = apk.get("size").asLongOrNull() ?: 0L

            UpdateInfo(
                versionName = versionName,
                apkUrl = apkUrl,
                assetName = assetName,
                notes = notes?.takeIf { it.isNotBlank() },
                publishedAt = publishedAt,
                assetSizeBytes = size
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Normalizes a GitHub tag (e.g. "v1.0.1", "1.0", "1.0.1-rc1") into a dotted
     * numeric version. A leading "v" and any non-numeric suffix on a segment are
     * dropped, so pre-release tags still compare as their base version. Returns
     * null if no numeric version can be extracted.
     */
    internal fun normalizeVersion(raw: String): String? {
        val cleaned = raw.trim().removePrefix("v")
        if (cleaned.isEmpty()) return null
        val segments = cleaned.split(".")
        // Keep the leading digits of each segment, stopping at the first segment
        // that has none.
        val numbers = segments
            .map { it.takeWhile(Char::isDigit) }
            .takeWhile { it.isNotEmpty() }
        if (numbers.isEmpty()) return null
        return numbers.joinToString(".")
    }

    /**
     * Compares two semantic versions numerically (major.minor.patch) and returns true
     * when [remote] is strictly newer than [local]. Any invalid part is treated as 0;
     * a missing minor/patch is treated as 0. Returns false for empty/invalid input.
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

    /** Splits a version string into a list of numeric segments (invalid -> null). */
    private fun parseSegments(version: String): List<Int>? {
        val cleaned = normalizeVersion(version) ?: return null
        return cleaned.split(".").map { seg ->
            seg.toIntOrNull() ?: return null
        }
    }

    /**
     * Reads a JsonElement as a string, returning null for a missing element, JSON
     * null, or a non-primitive value. GitHub returns explicit `null` for empty
     * `body` fields, which would otherwise throw.
     */
    private fun com.google.gson.JsonElement?.asStringOrNull(): String? {
        if (this == null || isJsonNull || !isJsonPrimitive) return null
        return asString
    }

    /** Reads a JsonElement as a long, returning null for missing/JSON null/non-numeric. */
    private fun com.google.gson.JsonElement?.asLongOrNull(): Long? {
        if (this == null || isJsonNull || !isJsonPrimitive) return null
        return runCatching { asLong }.getOrNull()
    }
}
