package com.vega.sting.updater

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.vega.sting.BuildConfig
import java.io.File

/**
 * A completed, verified release APK living in the app's canonical public
 * download folder. Exactly one of [file] / [uri] is set:
 *
 *  * [uri]  — API 29+ (MediaStore.Downloads entry in `Download/VEGA-STING/`)
 *  * [file] — API 26–28 (a plain file in `Download/VEGA-STING/`)
 *
 * Both forms are readable by the system Package Installer, and both are visible
 * to the user's file manager. The [file]/[uri] pair keeps the rest of the
 * updater (install controller, cleanup) source-agnostic.
 */
data class ApkArtifact(
    val displayName: String,
    val sizeBytes: Long,
    val file: File?,
    val uri: Uri?
) {
    /** Stable identifier used for logging and equality checks. */
    val reference: String get() = uri?.toString() ?: file?.absolutePath ?: displayName
}

/**
 * Centralised lifecycle owner for downloaded release APKs.
 *
 * **Single canonical location.** Completed APKs always land in the public
 * `Download/VEGA-STING/` folder on internal storage. Nothing is kept in
 * app-private storage once the download is verified, so the APK survives an
 * uninstall and can be used to recover from a manual downgrade.
 *
 * **Staging.** Downloads are written to a private `.part` file first. Only after
 * the bytes are verified (SHA-256, package, version, signer — see [ApkValidator])
 * is the file promoted to the public folder. A cancelled or invalid download
 * never leaves a partial file behind and never touches a previously verified
 * APK.
 *
 * **MediaStore vs legacy.** On API 29+ the public folder is published through
 * [MediaStore.Downloads] with `RELATIVE_PATH`, so no broad storage permission is
 * required. On API 26–28 the file is written directly; the app already declares
 * `WRITE_EXTERNAL_STORAGE` (maxSdkVersion 28) and requests legacy external
 * storage for its recording feature.
 */
object ApkStore {

    private const val TAG = "ApkStore"

    /** Public folder name under the device's Downloads directory. */
    const val FOLDER = "VEGA-STING"

    /** `Download/VEGA-STING/` relative path used by MediaStore on API 29+. */
    val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"

    /** Every publishable APK is named with this prefix (never matches other apps). */
    const val APK_PREFIX = "vega-sting-"

    private const val STAGING_DIR = "ota-staging"

    private const val APK_MIME = "application/vnd.android.package-archive"

    // ------------------------------------------------------------------
    // Locations
    // ------------------------------------------------------------------

    /** The public `Download/VEGA-STING/` directory (legacy file view). */
    fun publicDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)

    /** Private staging directory for in-progress `.part` downloads. */
    fun stagingDir(context: Context): File =
        File(context.cacheDir, STAGING_DIR).apply { mkdirs() }

    /** A private `.part` file for an in-progress download of [assetName]. */
    fun stagingFile(context: Context, assetName: String): File =
        File(stagingDir(context), sanitize(assetName) + ".part")

    /** True when the public folder can be written directly (legacy/API<29 path). */
    fun canWritePublicDir(): Boolean = try {
        val dir = publicDir()
        if (!dir.exists()) dir.mkdirs()
        dir.isDirectory && dir.canWrite()
    } catch (e: Exception) {
        Log.w(TAG, "Public folder not writable", e)
        false
    }

    // ------------------------------------------------------------------
    // Publish
    // ------------------------------------------------------------------

    /**
     * Promotes a fully verified [staging] file into the canonical public folder,
     * returning an [ApkArtifact] the installer can open. Returns null only when
     * the APK could not be published at all.
     *
     * The staging file is always deleted, whether or not publishing succeeds.
     */
    fun publish(context: Context, staging: File, assetName: String): ApkArtifact? {
        val safeName = sanitize(assetName).ensureApkExtension()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(context, staging, safeName)
                    ?: publishViaFile(staging, safeName)
            } else {
                publishViaFile(staging, safeName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not publish APK to the public folder", e)
            null
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    /** API 29+: create a Downloads entry and stream the verified bytes into it. */
    private fun publishViaMediaStore(context: Context, staging: File, displayName: String): ApkArtifact? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri: Uri = resolver.insert(collection, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { output ->
                staging.inputStream().use { input -> input.copyTo(output) }
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }

            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)

            Log.i(TAG, "Published APK via MediaStore: $displayName (${staging.length()} bytes)")
            return ApkArtifact(displayName = displayName, sizeBytes = staging.length(), file = null, uri = uri)
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore publish failed, removing pending entry", e)
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
    }

    /** API 26–28 (and MediaStore fallback): write a plain file into the folder. */
    private fun publishViaFile(staging: File, displayName: String): ApkArtifact? {
        val dir = publicDir()
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create public download folder ${dir.absolutePath}")
            return null
        }
        val target = uniqueFile(dir, displayName)
        val moved = try {
            if (staging.renameTo(target)) true
            else {
                staging.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not move APK into the public folder", e)
            false
        }
        if (!moved) {
            target.delete()
            return null
        }
        Log.i(TAG, "Published APK to file: ${target.absolutePath} (${target.length()} bytes)")
        return ApkArtifact(displayName = target.name, sizeBytes = target.length(), file = target, uri = null)
    }

    // ------------------------------------------------------------------
    // Completed-APK inventory (used for cleanup / reconciliation)
    // ------------------------------------------------------------------

    /** Names + sizes of every completed managed APK currently in the folder. */
    fun listCompleted(context: Context): List<ApkArtifact> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listViaMediaStore(context)
        } else {
            publicDir().listFiles()
                ?.filter { it.isFile && it.name.startsWith(APK_PREFIX) && it.name.endsWith(".apk") }
                ?.map { ApkArtifact(it.name, it.length(), it, null) }
                ?: emptyList()
        }
    }

    private fun listViaMediaStore(context: Context): List<ApkArtifact> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$RELATIVE_PATH%")
        val out = mutableListOf<ApkArtifact>()
        try {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)
                ?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol) ?: continue
                        if (!name.startsWith(APK_PREFIX) || !name.endsWith(".apk")) continue
                        val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol).toString())
                        out += ApkArtifact(name, cursor.getLong(sizeCol), file = null, uri = uri)
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate MediaStore downloads", e)
        }
        return out
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    /** Deletes a single artifact (MediaStore entry or file). */
    fun delete(context: Context, artifact: ApkArtifact) {
        try {
            if (artifact.uri != null) {
                context.contentResolver.delete(artifact.uri, null, null)
            } else {
                artifact.file?.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete ${artifact.displayName}", e)
        }
    }

    /** Removes any leftover staging `.part` files from previous attempts. */
    fun purgeStaging(context: Context) {
        stagingDir(context).listFiles()?.forEach { file ->
            if (file.name.endsWith(".part") && file.delete()) {
                Log.i(TAG, "Purged staged partial download ${file.name}")
            }
        }
    }

    /**
     * Removes managed APKs that are strictly older than the version currently
     * installed, keeping [keep] (an APK the user may still want to install) and
     * anything at least as new as the running build. Safe to call on every
     * launch: it only ever touches files in `Download/VEGA-STING/`.
     */
    fun cleanupObsolete(
        context: Context,
        installedVersionName: String = BuildConfig.VERSION_NAME,
        keep: ApkArtifact? = null
    ): List<ApkArtifact> {
        val removed = mutableListOf<ApkArtifact>()
        for (artifact in listCompleted(context)) {
            if (keep != null && artifact.reference == keep.reference) continue
            val apkVersionName = versionNameFromAsset(artifact.displayName) ?: continue
            if (UpdateChecker.compareVersions(apkVersionName, installedVersionName) < 0) {
                delete(context, artifact)
                removed += artifact
                Log.i(TAG, "Removed obsolete update APK ${artifact.displayName}")
            }
        }
        return removed
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Recovers the version name embedded in a managed asset filename, e.g.
     * `vega-sting-stable-2.0.5.apk` -> `2.0.5` or
     * `vega-sting-debug-2.0.6-dev.1.apk` -> `2.0.6-dev.1`. Null when the name is
     * not a recognisable managed APK.
     */
    internal fun versionNameFromAsset(name: String): String? {
        if (!name.startsWith(APK_PREFIX) || !name.endsWith(".apk")) return null
        val body = name.removePrefix(APK_PREFIX).removeSuffix(".apk")
        return when {
            body.startsWith("stable-") -> body.removePrefix("stable-")
            body.startsWith("debug-") -> body.removePrefix("debug-")
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.removeSuffix(".apk")
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i).apk")
            i++
        }
        return candidate
    }

    internal fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifEmpty { "vega-sting.apk" }
    }

    private fun String.ensureApkExtension(): String =
        if (endsWith(".apk", ignoreCase = true)) this else "$this.apk"
}
