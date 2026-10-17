package com.vega.sting.updater

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The machine-readable `update.json` manifest attached to every VEGA STING
 * release. It is generated from the verified APK (see scripts/package-release.ps1)
 * and is the updater's only trusted source of channel/package/version/checksum
 * metadata.
 */
data class ReleaseManifest(
    val schemaVersion: Int,
    val channel: UpdateChannel,
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val apkAsset: String,
    val sha256: String,
    val minSupportedVersionCode: Long,
    /**
     * APK size in bytes as declared by the manifest, or 0 when the release
     * predates the field. Optional so releases published before `apkSizeBytes`
     * existed keep working.
     */
    val apkSizeBytes: Long = 0L
) {
    /** True when [sha256] is a well-formed 64-character hex digest. */
    fun hasValidSha256(): Boolean = SHA256_REGEX.matches(sha256)

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1

        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

        /**
         * Strictly parses a manifest body. Returns null for malformed JSON,
         * unknown schema versions, an unrecognised channel, or missing/blank
         * required fields, so a corrupt manifest can never be treated as valid.
         */
        fun parse(json: String): ReleaseManifest? {
            return try {
                val root = JsonParser.parseString(json).asJsonObject

                val schema = root.get("schemaVersion").asIntOrNull() ?: return null
                if (schema != SUPPORTED_SCHEMA_VERSION) return null

                val channel = UpdateChannel.fromRaw(root.get("channel").asStringOrNull()) ?: return null
                val versionName = root.get("versionName").asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
                val versionCode = root.get("versionCode").asLongOrNull()?.takeIf { it > 0 } ?: return null
                val packageName = root.get("packageName").asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
                val apkAsset = root.get("apkAsset").asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
                val sha256 = root.get("sha256").asStringOrNull()?.trim()?.lowercase() ?: return null
                val minSupported = root.get("minSupportedVersionCode").asLongOrNull() ?: 1L
                // Optional: releases published before this field existed omit it.
                val apkSize = root.get("apkSizeBytes").asLongOrNull()?.takeIf { it > 0 } ?: 0L

                ReleaseManifest(
                    schemaVersion = schema,
                    channel = channel,
                    versionName = versionName,
                    versionCode = versionCode,
                    packageName = packageName,
                    apkAsset = apkAsset,
                    sha256 = sha256,
                    minSupportedVersionCode = minSupported,
                    apkSizeBytes = apkSize
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

private fun com.google.gson.JsonElement?.asStringOrNull(): String? {
    if (this == null || isJsonNull || !isJsonPrimitive) return null
    return asString
}

private fun com.google.gson.JsonElement?.asLongOrNull(): Long? {
    if (this == null || isJsonNull || !isJsonPrimitive) return null
    return runCatching { asLong }.getOrNull()
}

private fun com.google.gson.JsonElement?.asIntOrNull(): Int? {
    if (this == null || isJsonNull || !isJsonPrimitive) return null
    return runCatching { asInt }.getOrNull()
}
