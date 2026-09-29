package com.vega.sting.ui.update

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.vega.sting.BuildConfig
import com.vega.sting.core.RecordingStateManager
import com.vega.sting.ui.theme.AccentOrange
import com.vega.sting.ui.theme.Panel
import com.vega.sting.ui.theme.TextPrimary
import com.vega.sting.updater.InstallController
import com.vega.sting.updater.UpdateChecker
import com.vega.sting.updater.UpdaterManager
import java.io.File

/**
 * Owns the OTA update dialog state and drives the whole flow:
 * check -> available -> download -> ready -> system installer.
 *
 * Mounted once from MainActivity. Prompting mid-recording is suppressed; if an
 * update was found but suppressed, the prompt is re-offered once recording stops
 * so a user is never permanently blocked from updating.
 */
@Composable
fun UpdateHost(
    /** Bumped by Settings to trigger a manual, unthrottled check. */
    manualCheckToken: Int = 0,
    /**
     * When false the automatic startup check is deferred. The effect re-runs when
     * this flips back to true, so an update check suppressed by an active recording
     * happens once recording stops rather than being lost.
     */
    autoCheckEnabled: Boolean = true
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val updater = remember { UpdaterManager(context.applicationContext) }

    var available by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var downloadJob by remember { mutableStateOf<UpdaterManager.DownloadJob?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var readyToInstall by remember { mutableStateOf<File?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var checkFailed by remember { mutableStateOf(false) }
    var showUpToDate by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    // Found while recording; re-offered when recording ends.
    var deferredUpdate by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    fun startCheck(manual: Boolean) {
        if (isChecking) return
        if (!manual && RecordingStateManager.isRecording()) return

        isChecking = true
        checkFailed = false

        if (manual) {
            // A manual check should surface a version the user previously skipped.
            updater.clearIgnoredVersion()
        }

        val started = updater.checkForUpdates(
            manual = manual,
            isRecording = { RecordingStateManager.isRecording() }
        ) { result ->
            isChecking = false
            when (result) {
                is UpdaterManager.CheckResult.UpdateAvailable -> {
                    if (RecordingStateManager.isRecording()) {
                        deferredUpdate = result.update
                    } else {
                        available = result.update
                    }
                }
                UpdaterManager.CheckResult.UpToDate -> if (manual) showUpToDate = true
                is UpdaterManager.CheckResult.Failed -> {
                    // A silent automatic check should not nag; only report a manual failure.
                    if (manual) checkFailed = true
                }
            }
        }

        // Suppressed (throttled or recording): never leave a stuck spinner.
        if (!started) isChecking = false
    }

    LaunchedEffect(autoCheckEnabled) {
        if (autoCheckEnabled) startCheck(manual = false)
    }

    LaunchedEffect(manualCheckToken) {
        if (manualCheckToken > 0) startCheck(manual = true)
    }

    // Re-offer a suppressed prompt when recording finishes. Only polls while a
    // prompt is actually deferred, so the steady-state cost is zero.
    LaunchedEffect(deferredUpdate) {
        if (deferredUpdate != null) {
            while (RecordingStateManager.isRecording()) {
                delay(1_000)
            }
            deferredUpdate?.let {
                available = it
                deferredUpdate = null
            }
        }
    }

    if (isChecking && available == null && downloadJob == null && !checkFailed) {
        CheckingDialog()
    }

    if (checkFailed) {
        UpdateCheckFailedDialog(onDismiss = { checkFailed = false })
    }

    downloadError?.let { reason ->
        UpdateDownloadFailedDialog(
            reason = reason,
            onDismiss = { downloadError = null }
        )
    }

    if (showUpToDate) {
        UpToDateDialog(onDismiss = { showUpToDate = false })
    }

    val update = available
    if (update != null) {
        val job = downloadJob
        if (job == null) {
            UpdateAvailableDialog(
                update = update,
                currentVersion = BuildConfig.VERSION_NAME,
                onDownload = {
                    // Allow resuming progress even if this version was skipped earlier.
                    updater.clearIgnoredVersion()
                    progress = 0f
                    downloadedBytes = 0L
                    downloadJob = updater.download(
                        update = update,
                        onProgress = { downloaded, total ->
                            downloadedBytes = downloaded
                            progress = if (total > 0L) {
                                (downloaded.toFloat() / total.toFloat()) * 100f
                            } else {
                                -1f // unknown total
                            }
                        },
                        onDone = { file ->
                            downloadJob = null
                            progress = 0f
                            downloadedBytes = 0L
                            readyToInstall = file
                        },
                        onError = { message ->
                            downloadJob = null
                            progress = 0f
                            Log.w("UpdateHost", "Download failed: $message")
                            available = null
                            downloadError = message
                        }
                    )
                },
                onSkip = {
                    updater.ignoreVersion(update.versionName)
                    available = null
                },
                onLater = { available = null }
            )
        } else {
            UpdateDownloadProgressDialog(
                progress = progress,
                downloadedBytes = downloadedBytes,
                onCancel = {
                    job.cancel()
                    downloadJob = null
                    progress = 0f
                    available = null
                }
            )
        }
    }

    val apk = readyToInstall
    if (apk != null && activity == null) {
        // No Activity to hand the installer off to (e.g. host in a test/preview).
        LaunchedEffect(apk) { readyToInstall = null }
    } else if (apk != null && activity != null) {
        // canInstallApks() is re-evaluated on every recomposition, so returning
        // from Settings with the toggle enabled advances to the install prompt.
        if (!InstallController.canInstallApks(context)) {
            UpdatePermissionRequiredDialog(
                onOpenSettings = { InstallController.openUnknownSourcesSettings(activity) },
                onDismiss = {
                    readyToInstall = null
                    updater.purgeStaleDownloads(keep = apk)
                }
            )
        } else {
            UpdateReadyToInstallDialog(
                onInstall = {
                    InstallController.startInstall(activity, apk)
                    updater.purgeStaleDownloads(keep = apk)
                    readyToInstall = null
                },
                onDismiss = {
                    readyToInstall = null
                    updater.purgeStaleDownloads(keep = apk)
                }
            )
        }
    }
}

/** Small terminal-styled dialog shown while a check is in flight. */
@Composable
private fun CheckingDialog() {
    AlertDialog(
        onDismissRequest = { },
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Text(
                text = "Checking for updates",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Text(
                text = "Contacting GitHub…",
                fontFamily = FontFamily.Monospace
            )
        },
        confirmButton = {}
    )
}
