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
 * Skipped versions are stored per channel, so a version skipped in DEBUG cannot
 * suppress a STABLE release with the same version name (and vice versa).
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
     * Downloads and verifies the APK for [update] into app-private storage.
     * [onProgress] is invoked on the main thread with (bytesDownloaded,
     * totalBytes); totalBytes is -1 when the server sends no Content-Length.
     * The download is validated (SHA-256, package, version, signing certificate)
     * before [onDone] receives the [File]; a candidate that cannot be installed
     * is deleted and reported through [onError]. The download can be cancelled
     * by the caller (e.g. dialog dismiss) via the returned cancellation state.
     */
    fun download(
        update: UpdateChecker.UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit
    ): DownloadJob {
        val job = DownloadJob()
        Thread {
            var connection: HttpURLConnection? = null
            try {
                if (job.cancelled) return@Thread

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
                    mainHandler.post { onError("Server returned HTTP $code") }
                    return@Thread
                }

                val contentLength = connection.contentLengthLong
                val dir = File(context.filesDir, "updates").apply { mkdirs() }
                val target = File(dir, safeAssetName(update.assetName))

                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var lastReport = 0L

                        while (true) {
                            if (job.cancelled) {
                                output.flush()
                                target.delete()
                                return@Thread
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

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
                    return@Thread
                }

                // Never offer an APK that Android would reject.
                val validation = ApkValidator.validateDownloadedApk(
                    context = context,
                    file = target,
                    expectedPackage = context.packageName,
                    expectedVersionCode = update.versionCode,
                    expectedVersionName = update.versionName,
                    expectedSha256 = update.sha256
                )
                if (validation is ApkValidator.Result.Invalid) {
                    Log.w(TAG, "Downloaded APK rejected: ${validation.reason}")
                    target.delete()
                    mainHandler.post { onError(validation.reason) }
                    return@Thread
                }

                Log.i(TAG, "APK downloaded and verified: ${target.absolutePath} (${target.length()} bytes)")
                mainHandler.post { onDone(target) }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                if (!job.cancelled) {
                    mainHandler.post { onError(e.message ?: "Download failed") }
                }
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

    private fun safeAssetName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (sanitized.isEmpty()) "vega-sting.apk" else sanitized
    }

    /**
     * Removes stale APKs left in filesDir/updates from previous attempts, keeping
     * only [keep]. Prevents unbounded disk growth from repeated update attempts.
     */
    fun purgeStaleDownloads(keep: File? = null) {
        val dir = File(context.filesDir, "updates")
        dir.listFiles()?.forEach { file ->
            if (keep == null || file.absolutePath != keep.absolutePath) {
                if (file.delete()) Log.i(TAG, "Purged stale update file ${file.name}")
            }
        }
    }

    /** Mutable cancellation flag shared with the download thread. */
    class DownloadJob {
        @Volatile var cancelled: Boolean = false
        fun cancel() { cancelled = true }
    }
}
