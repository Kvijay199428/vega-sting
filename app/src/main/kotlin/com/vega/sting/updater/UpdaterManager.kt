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
 * Orchestrates the OTA update flow: checking for a newer release and downloading
 * the APK with progress callbacks. All network work runs off the main thread;
 * result/progress callbacks are delivered on the main thread.
 */
class UpdaterManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "UpdaterManager"
        private const val PREFS_NAME = "vega_sting_updater"
        private const val KEY_IGNORED_VERSION = "ignored_version_name"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** Re-check interval for the automatic startup prompt. */
        private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours

        private const val KEY_LAST_AUTO_CHECK = "last_auto_check_ms"
    }

    /** Outcome of an update check, always delivered on the main thread. */
    sealed interface CheckResult {
        /** A strictly newer release is available. */
        data class UpdateAvailable(val update: UpdateChecker.UpdateInfo) : CheckResult

        /** The installed version is current, or the release was already ignored. */
        data object UpToDate : CheckResult

        /** The release could not be fetched or parsed. */
        data class Failed(val reason: String) : CheckResult
    }

    /**
     * Runs an update check in the background and always reports exactly one
     * [CheckResult] on the main thread, so callers can reliably clear a
     * "checking" state instead of hanging on failure.
     *
     * A manual check ([manual] = true) bypasses the 6-hour throttle and re-offers a
     * previously skipped version.
     *
     * @param isRecording guards against starting a check mid-recording
     * @return true if a check was actually started; false if it was suppressed
     */
    fun checkForUpdates(
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
            val latest = UpdateChecker.fetchLatestRelease(
                BuildConfig.GITHUB_OWNER,
                BuildConfig.GITHUB_REPO
            )

            if (latest == null) {
                val reason = "Could not reach the GitHub Releases API"
                Log.w(TAG, reason)
                mainHandler.post { onResult(CheckResult.Failed(reason)) }
                return@Thread
            }

            val localVersion = BuildConfig.VERSION_NAME
            if (!UpdateChecker.isNewer(latest.versionName, localVersion)) {
                Log.i(TAG, "Already up to date (current=$localVersion, latest=${latest.versionName})")
                mainHandler.post { onResult(CheckResult.UpToDate) }
                return@Thread
            }
            if (latest.versionName == ignoredVersion()) {
                Log.i(TAG, "Version ${latest.versionName} was ignored by user")
                mainHandler.post { onResult(CheckResult.UpToDate) }
                return@Thread
            }

            mainHandler.post { onResult(CheckResult.UpdateAvailable(latest)) }
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
     * Downloads the APK for [update] into app-private storage. [onProgress] is
     * invoked on the main thread with (bytesDownloaded, totalBytes); totalBytes is
     * -1 when the server sends no Content-Length. [onDone] receives the downloaded
     * [File], [onError] the failure reason. The download can be cancelled by the
     * caller (e.g. dialog dismiss) via the returned cancellation state.
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

                Log.i(TAG, "APK downloaded: ${target.absolutePath} (${target.length()} bytes)")
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

    /** Marks a version as ignored so it is not prompted again until the next release. */
    fun ignoreVersion(versionName: String) {
        prefs.edit().putString(KEY_IGNORED_VERSION, versionName).apply()
    }

    /** Clears a previously ignored version (used by the manual "check for updates"). */
    fun clearIgnoredVersion() {
        prefs.edit().remove(KEY_IGNORED_VERSION).apply()
    }

    private fun ignoredVersion(): String? = prefs.getString(KEY_IGNORED_VERSION, null)

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
