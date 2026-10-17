package com.vega.sting.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import com.vega.sting.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Verifies that a downloaded APK can actually replace the installed app.
 *
 * Android only accepts an in-place update when the candidate APK has the same
 * package id, a compatible signing certificate and a version code that is not
 * lower than the installed one. The channel the user selected does NOT relax any
 * of these rules, so every download is checked here before it is ever handed to
 * the system Package Installer.
 */
object ApkValidator {

    private const val TAG = "ApkValidator"
    private const val SHA256_HEX_LENGTH = 64

    /** A candidate APK that passed every check, or the reason it failed. */
    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reason: String) : Result
    }

    /** Facts read from an APK or an installed package. */
    data class ApkFacts(
        val packageName: String,
        val versionCode: Long,
        val versionName: String?,
        val signerDigests: List<String>
    )

    // ------------------------------------------------------------------
    // Pure helpers
    // ------------------------------------------------------------------

    /** Lowercase hex SHA-256 of [file]'s bytes. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /** True when [file]'s bytes hash to [expectedHex] (case-insensitive). */
    fun verifyChecksum(file: File, expectedHex: String?): Boolean {
        val expected = expectedHex?.trim()?.lowercase() ?: return false
        if (expected.length != SHA256_HEX_LENGTH) return false
        return constantTimeEquals(sha256(file), expected)
    }

    /** True when [hex] is a well-formed SHA-256 digest. */
    fun isSha256(hex: String?): Boolean {
        val value = hex?.trim() ?: return false
        return value.length == SHA256_HEX_LENGTH && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }

    // ------------------------------------------------------------------
    // Android framework
    // ------------------------------------------------------------------

    /** Reads package id, version and signer digests from an APK on disk. */
    fun readApkFacts(context: Context, file: File): ApkFacts? {
        return try {
            val info = context.packageManager
                .getPackageArchiveInfo(file.absolutePath, signingFlags())
                ?: return null
            info.toFacts()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read APK metadata", e)
            null
        }
    }

    /** Reads the installed app's signer digests. */
    fun installedSignerDigests(context: Context): List<String> {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, signingFlags())
            info.signerDigests()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read installed signer", e)
            emptyList()
        }
    }

    /**
     * Full pre-install validation of a downloaded APK against the release
     * manifest and the installed application.
     */
    fun validateDownloadedApk(
        context: Context,
        file: File,
        expectedPackage: String,
        expectedVersionCode: Long,
        expectedVersionName: String?,
        expectedSha256: String?,
        expectedSizeBytes: Long = 0L,
        allowDowngrade: Boolean = false
    ): Result {
        if (!file.exists() || file.length() == 0L) {
            return Result.Invalid("The downloaded file is empty.")
        }
        if (expectedSizeBytes > 0L && file.length() != expectedSizeBytes) {
            return Result.Invalid(
                "The downloaded APK is ${file.length()} bytes, but the release declares " +
                    "$expectedSizeBytes bytes."
            )
        }
        if (!verifyChecksum(file, expectedSha256)) {
            return Result.Invalid("The downloaded APK failed its SHA-256 integrity check.")
        }

        val facts = readApkFacts(context, file)
            ?: return Result.Invalid("The downloaded file is not a readable APK.")

        if (facts.packageName != expectedPackage) {
            return Result.Invalid(
                "The APK targets package ${facts.packageName}, but this app is $expectedPackage."
            )
        }
        if (expectedVersionCode > 0L && facts.versionCode != expectedVersionCode) {
            return Result.Invalid(
                "The APK version code ${facts.versionCode} does not match the release manifest."
            )
        }
        if (!expectedVersionName.isNullOrBlank() && facts.versionName != expectedVersionName) {
            return Result.Invalid(
                "The APK version name ${facts.versionName} does not match the release manifest."
            )
        }
        if (!allowDowngrade && facts.versionCode <= BuildConfig.VERSION_CODE) {
            return Result.Invalid(
                "The APK version code ${facts.versionCode} is not newer than the installed " +
                    "build (${BuildConfig.VERSION_CODE}); Android rejects downgrades."
            )
        }

        val installedSigners = installedSignerDigests(context)
        if (installedSigners.isEmpty()) {
            return Result.Invalid("Could not determine the installed app's signing certificate.")
        }
        if (facts.signerDigests.isEmpty()) {
            return Result.Invalid("The downloaded APK is not signed.")
        }
        if (facts.signerDigests != installedSigners) {
            return Result.Invalid(
                "The APK is signed with a different certificate than the installed app, " +
                    "so Android cannot install it as an update."
            )
        }

        return Result.Valid
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun PackageInfo.toFacts(): ApkFacts = ApkFacts(
        packageName = packageName,
        versionCode = versionCodeCompat(),
        versionName = versionName,
        signerDigests = signerDigests()
    )

    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun PackageInfo.signerDigests(): List<String> {
        val certs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            signatures
        }
        return certs.orEmpty().map { it.toByteArray().toSha256Hex() }.distinct().sorted()
    }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun ByteArray.toSha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
