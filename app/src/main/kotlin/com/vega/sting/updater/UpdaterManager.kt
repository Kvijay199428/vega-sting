package com.vega.sting.updater

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vega.sting.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Orchestrates the OTA update flow for a selected [UpdateChannel]: checking for
 * a newer release and downloading the APK with progress callbacks. All network
 * work runs off the main thread; result/progress callbacks are delivered on the
 * main thread.
 *
 * Downloaded APKs are staged privately, verified, then promoted to the canonical
 * public `Download/VEGA-STING/` folder by [ApkStore]. Skipped versions are stored
 * per channel, so a version skipped in DEBUG cannot suppress a STABLE release
 * with the same version name (and vice versa).
 */
class UpdaterManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "UpdaterManager"
        private const val PREFS_NAME = "vega_sting_updater"
        private const val KEY_IGNORED_VERSION_PREFIX = "ignored_version_"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** How often to re-check free space while streaming an APK. */
        private const val FREE_SPACE_RECHECK_BYTES = 4L * 1024L * 1024L

        /** Re-check interval for the automatic startup prompt. */
        private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours

        private const val KEY_LAST_AUTO_CHECK = "last_auto_check_ms"

        /** Version code of a system-installer launch that hasn't been confirmed. */
        private const val KEY_PENDING_INSTALL_VERSION = "pending_install_version_code"
    }

    /** Outcome of an update check, always delivered on the main thread. */
    sealed interface CheckResult {
        /** A strictly newer, installable release is available. */
        data class UpdateAvailable(val update: UpdateChecker.UpdateInfo) : CheckResult

        /** The installed version is current, or the release was already ignored. */
        data object UpToDate : CheckResult

        /**
         * The selected channel has published nothing the updater can use yet.
         * The channel preference is kept; [reason] explains the situation.
         */
        data class ChannelAhead(val reason: String) : CheckResult

        /**
         * Releases exist for the selected channel but none can replace the
         * installed app (wrong package, incompatible signature, downgrade, …).
         */
        data class Incompatible(val reason: String) : CheckResult

        /** The release could not be fetched or resolved. */
        data class Failed(val reason: String) : CheckResult
    }

    /** Outcome of an APK download, always delivered on the main thread. */
    sealed interface DownloadResult {
        /** The user cancelled; any partial file was removed. */
        data object Cancelled : DownloadResult

        /** Verified and published to the public folder. */
        data class Success(val artifact: ApkArtifact) : DownloadResult

        /** Not enough free space to start or finish the download. */
        data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : DownloadResult

        /** The download or verification failed. */
        data class Failed(val reason: String) : DownloadResult
    }

    /**
     * Runs an update check for [channel] in the background and always reports
     * exactly one [CheckResult] on the main thread, so callers can reliably
     * clear a "checking" state instead of hanging on failure.
     *
     * A manual check ([manual] = true) bypasses the 6-hour throttle and re-offers
     * a previously skipped version.
     *
     * @param isRecording guards against starting a check mid-recording
     * @return true if a check was actually started; false if it was suppressed
     */
    fun checkForUpdates(
        channel: UpdateChannel,
        manual: Boolean,
        isRecording: () -> Boolean = { false },
        onResult: (CheckResult) -> Unit
    ): Boolean {
        if (!manual && isRecording()) {
            Log.i(TAG, "Skipping update check: recording in progress")
            return false
        }
        if (!manual && isWithinThrottleWindow()) {
            Log.i(TAG, "Skipping update check: within throttle window")
            return false
        }
        recordCheckTime()

        Thread {
            val result = UpdateChecker.fetchUpdate(
                owner = BuildConfig.GITHUB_OWNER,
                repo = BuildConfig.GITHUB_REPO,
                channel = channel,
                installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                installedPackageName = context.packageName,
                installedChannel = UpdateChannel.installed(),
                installedVersionName = BuildConfig.VERSION_NAME,
                // A manual check (the channel switch and "Check for Updates")
                // may offer the target channel's newest build even when it is
                // older; the throttled automatic check stays quiet instead.
                allowSwitchDowngrade = manual
            )

            val check = when (result) {
                is UpdateChecker.SelectionResult.Available -> {
                    if (result.update.versionName == ignoredVersion(channel)) {
                        Log.i(TAG, "${result.update.versionName} was skipped for ${channel.label}")
                        CheckResult.UpToDate
                    } else {
                        CheckResult.UpdateAvailable(result.update)
                    }
                }
                UpdateChecker.SelectionResult.UpToDate -> CheckResult.UpToDate
                is UpdateChecker.SelectionResult.ChannelAhead -> CheckResult.ChannelAhead(result.reason)
                is UpdateChecker.SelectionResult.PolicyViolation -> {
                    // Two published APKs sharing a version code is a release
                    // process bug, never an update to offer. Surface it as a
                    // diagnostic and leave the installed build alone.
                    Log.w(TAG, "Version-policy violation: ${result.reason}")
                    CheckResult.Incompatible(result.reason)
                }
                is UpdateChecker.SelectionResult.Rejected -> CheckResult.Incompatible(result.reason)
                is UpdateChecker.SelectionResult.Failed -> CheckResult.Failed(result.reason)
            }
            mainHandler.post { onResult(check) }
        }.start()
        return true
    }

    private fun isWithinThrottleWindow(): Boolean {
        val last = prefs.getLong(KEY_LAST_AUTO_CHECK, 0L)
        return System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS
    }

    private fun recordCheckTime() {
        prefs.edit().putLong(KEY_LAST_AUTO_CHECK, System.currentTimeMillis()).apply()
    }

    /**
     * Downloads and verifies the APK for [update], then publishes it to the
     * canonical public `Download/VEGA-STING/` folder.
     *
     * [onProgress] is invoked on the main thread with (bytesDownloaded,
     * totalBytes); totalBytes is -1 when the server sends no Content-Length.
     * The download is validated (size, SHA-256, package, version, signer) before
     * it is promoted; an invalid candidate is deleted and reported through
     * [onResult]. A storage preflight runs before the transfer and is re-checked
     * as data arrives, so an oversized download cannot fill the volume.
     *
     * @param allowDowngrade permit a target whose version code is lower than the
     *   installed build (manual channel-downgrade only); the APK is still
     *   verified for package, signature, checksum and size.
     * @return a handle whose [DownloadJob.cancel] aborts the transfer
     */
    fun download(
        update: UpdateChecker.UpdateInfo,
        allowDowngrade: Boolean = false,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onResult: (DownloadResult) -> Unit
    ): DownloadJob {
        val job = DownloadJob()
        Thread {
            var connection: HttpURLConnection? = null
            var staging: File? = null
            try {
                // ---- storage preflight -------------------------------------
                val expected = update.expectedSizeBytes
                val volume = android.os.Environment.getExternalStorageDirectory()
                val available = StoragePreflight.availableBytes(volume)
                if (available >= 0L && !StoragePreflight.hasRoom(available, expected)) {
                    val required = StoragePreflight.requiredBytes(expected)
                    mainHandler.post { onResult(DownloadResult.InsufficientStorage(required, available)) }
                    return@Thread
                }

                if (job.cancelled) { mainHandler.post { onResult(DownloadResult.Cancelled) }; return@Thread }

                connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", "VEGA-STING-Updater")
                    setRequestProperty("Accept", "application/octet-stream")
                    // GitHub redirects asset downloads to a CDN host.
                    instanceFollowRedirects = true
                }

                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    mainHandler.post { onResult(DownloadResult.Failed("Server returned HTTP $code")) }
                    return@Thread
                }

                val contentLength = connection.contentLengthLong
                val target = ApkStore.stagingFile(context, update.assetName)
                staging = target
                if (target.exists()) target.delete()

                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var lastReport = 0L
                        var lastSpaceCheck = 0L

                        while (true) {
                            if (job.cancelled) {
                                output.flush()
                                target.delete()
                                mainHandler.post { onResult(DownloadResult.Cancelled) }
                                return@Thread
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            // Re-check free space periodically so a stream that
                            // outgrows the preflight estimate cannot fill the
                            // volume before failing.
                            if (downloaded - lastSpaceCheck >= FREE_SPACE_RECHECK_BYTES) {
                                lastSpaceCheck = downloaded
                                val free = StoragePreflight.availableBytes(volume)
                                if (free >= 0L && free < StoragePreflight.MIN_MARGIN_BYTES) {
                                    output.flush()
                                    target.delete()
                                    val required = StoragePreflight.requiredBytes(expected)
                                    mainHandler.post {
                                        onResult(DownloadResult.InsufficientStorage(required, free))
                                    }
                                    return@Thread
                                }
                            }

                            // Throttle UI updates to ~every 150ms to avoid chattiness.
                            if (downloaded - lastReport >= 150_000 || downloaded == contentLength) {
                                lastReport = downloaded
                                mainHandler.post { onProgress(downloaded, contentLength) }
                            }
                        }
                    }
                }

                if (job.cancelled) {
                    target.delete()
                    mainHandler.post { onResult(DownloadResult.Cancelled) }
                    return@Thread
                }

                // Never publish an APK that Android would reject (or that does
                // not match its release manifest).
                val validation = ApkValidator.validateDownloadedApk(
                    context = context,
                    file = target,
                    expectedPackage = context.packageName,
                    expectedVersionCode = update.versionCode,
                    expectedVersionName = update.versionName,
                    expectedSha256 = update.sha256,
                    expectedSizeBytes = update.expectedSizeBytes,
                    allowDowngrade = allowDowngrade
                )
                if (validation is ApkValidator.Result.Invalid) {
                    Log.w(TAG, "Downloaded APK rejected: ${validation.reason}")
                    target.delete()
                    mainHandler.post { onResult(DownloadResult.Failed(validation.reason)) }
                    return@Thread
                }

                // Promote the verified file into the public folder.
                val artifact = ApkStore.publish(context, target, update.assetName)
                if (artifact == null) {
                    mainHandler.post {
                        onResult(DownloadResult.Failed("Could not save the APK to the Downloads folder."))
                    }
                    return@Thread
                }

                Log.i(TAG, "APK verified and published: ${artifact.reference} (${artifact.sizeBytes} bytes)")
                mainHandler.post { onResult(DownloadResult.Success(artifact)) }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                staging?.delete()
                // Always report an outcome so the caller never holds a job that
                // will never complete.
                val outcome = if (job.cancelled) {
                    DownloadResult.Cancelled
                } else {
                    DownloadResult.Failed(e.message ?: "Download failed")
                }
                mainHandler.post { onResult(outcome) }
            } finally {
                connection?.disconnect()
            }
        }.start()
        return job
    }

    /** Marks a version as ignored for [channel] so it is not prompted again. */
    fun ignoreVersion(channel: UpdateChannel, versionName: String) {
        prefs.edit().putString(ignoredKey(channel), versionName).apply()
    }

    /** Clears the previously ignored version for [channel]. */
    fun clearIgnoredVersion(channel: UpdateChannel) {
        prefs.edit().remove(ignoredKey(channel)).apply()
    }

    private fun ignoredVersion(channel: UpdateChannel): String? = prefs.getString(ignoredKey(channel), null)

    private fun ignoredKey(channel: UpdateChannel): String = "$KEY_IGNORED_VERSION_PREFIX${channel.name}"

    /**
     * Records that the system installer was launched for [versionCode].
     *
     * Only downgrade attempts are recorded. `Intent.ACTION_VIEW` gives no result
     * callback, so if Android refuses the install (it won't replace a newer app
     * with an older one) the only way to notice is that the running version is
     * still not [versionCode] on the next launch.
     */
    fun markInstallLaunched(versionCode: Long) {
        prefs.edit().putLong(KEY_PENDING_INSTALL_VERSION, versionCode).apply()
    }

    /** The unconfirmed installer launch's version code, or null when there is none. */
    fun pendingInstallVersionCode(): Long? =
        prefs.getLong(KEY_PENDING_INSTALL_VERSION, -1L).takeIf { it > 0L }

    /** Clears the pending installer launch, whether it succeeded or was refused. */
    fun clearPendingInstall() {
        prefs.edit().remove(KEY_PENDING_INSTALL_VERSION).apply()
    }

    /**
     * Reconciles the public `Download/VEGA-STING/` folder with the running
     * build, deleting only managed APKs that are strictly older than the
     * installed version (an installed update no longer needs its installer).
     *
     * Safe to call on every launch: it never touches files that do not carry the
     * managed `vega-sting-` prefix, and never deletes an artifact passed as
     * [keep] (an APK the user may still choose to install).
     */
    fun reconcileDownloads(keep: ApkArtifact? = null) {
        runCatching {
            ApkStore.purgeStaging(context)
            ApkStore.cleanupObsolete(context, keep = keep)
        }.onFailure { Log.w(TAG, "Download reconciliation failed", it) }
    }

    /** Mutable cancellation flag shared with the download thread. */
    class DownloadJob {
        @Volatile var cancelled: Boolean = false
        fun cancel() { cancelled = true }
    }
}
