package com.vega.sting.ui.update

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.vega.sting.BuildConfig
import com.vega.sting.core.RecordingStateManager
import com.vega.sting.settings.SettingsManager
import com.vega.sting.ui.theme.AccentOrange
import com.vega.sting.ui.theme.Panel
import com.vega.sting.ui.theme.TextPrimary
import com.vega.sting.updater.InstallController
import com.vega.sting.updater.UpdateChannel
import com.vega.sting.updater.UpdateChecker
import com.vega.sting.updater.UpdaterManager
import java.io.File


@Composable
fun UpdateHost(
    
    manualCheckToken: Int = 0,
    
    autoCheckEnabled: Boolean = true
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val updater = remember { UpdaterManager(context.applicationContext) }
    val settingsManager = remember { SettingsManager(context.applicationContext) }

    // The selected channel drives which releases are checked. It is read fresh
    // from DataStore when a check starts so a channel switch is always respected.
    val selectedChannel by settingsManager.selectedUpdateChannel
        .collectAsState(initial = UpdateChannel.installed())

    var available by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var downloadJob by remember { mutableStateOf<UpdaterManager.DownloadJob?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var readyToInstall by remember { mutableStateOf<File?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var checkFailed by remember { mutableStateOf(false) }
    var showUpToDate by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var incompatibleReason by remember { mutableStateOf<String?>(null) }
    var channelAheadReason by remember { mutableStateOf<String?>(null) }

    
    var deferredUpdate by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    fun startCheck(manual: Boolean) {
        if (isChecking) return
        if (!manual && RecordingStateManager.isBusy()) return

        isChecking = true
        checkFailed = false

        scope.launch {
            // Read the persisted channel at check time so the settings screen can
            // save a switch and immediately trigger a check without racing.
            val channel = settingsManager.selectedUpdateChannel.first()

            if (manual) {
                updater.clearIgnoredVersion(channel)
            }

            val started = updater.checkForUpdates(
                channel = channel,
                manual = manual,
                isRecording = { RecordingStateManager.isBusy() }
            ) { result ->
                isChecking = false
                when (result) {
                    is UpdaterManager.CheckResult.UpdateAvailable -> {
                        if (RecordingStateManager.isBusy()) {
                            deferredUpdate = result.update
                        } else {
                            available = result.update
                        }
                    }
                    UpdaterManager.CheckResult.UpToDate -> if (manual) showUpToDate = true
                    is UpdaterManager.CheckResult.ChannelAhead -> {
                        if (manual) channelAheadReason = result.reason
                    }
                    is UpdaterManager.CheckResult.Incompatible -> {
                        if (manual) incompatibleReason = result.reason
                    }
                    is UpdaterManager.CheckResult.Failed -> {
                        if (manual) checkFailed = true
                    }
                }
            }

            if (!started) isChecking = false
        }
    }

    LaunchedEffect(autoCheckEnabled) {
        if (autoCheckEnabled) startCheck(manual = false)
    }

    LaunchedEffect(manualCheckToken) {
        if (manualCheckToken > 0) startCheck(manual = true)
    }

    
    
    
    
    LaunchedEffect(deferredUpdate) {
        if (deferredUpdate != null) {
            while (RecordingStateManager.isBusy()) {
                delay(1_000)
            }
            deferredUpdate?.let {
                available = it
                deferredUpdate = null
            }
        }
    }

    if (isChecking && available == null && downloadJob == null && !checkFailed &&
        incompatibleReason == null && channelAheadReason == null
    ) {
        CheckingDialog()
    }

    if (checkFailed) {
        UpdateCheckFailedDialog(onDismiss = { checkFailed = false })
    }

    incompatibleReason?.let { reason ->
        UpdateIncompatibleDialog(
            reason = reason,
            onDismiss = { incompatibleReason = null }
        )
    }

    channelAheadReason?.let { reason ->
        UpdateChannelSwitchBlockedDialog(
            reason = reason,
            onDismiss = { channelAheadReason = null }
        )
    }

    downloadError?.let { reason ->
        UpdateDownloadFailedDialog(
            reason = reason,
            onDismiss = { downloadError = null }
        )
    }

    if (showUpToDate) {
        UpToDateDialog(
            channelLabel = selectedChannel.label,
            installedChannelLabel = UpdateChannel.installed().label,
            installedVersionName = BuildConfig.VERSION_NAME,
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            onDismiss = { showUpToDate = false }
        )
    }

    val update = available
    if (update != null) {
        val job = downloadJob
        if (job == null) {
            UpdateAvailableDialog(
                update = update,
                installedChannel = UpdateChannel.installed(),
                installedVersionName = BuildConfig.VERSION_NAME,
                installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                onDownload = {
                    updater.clearIgnoredVersion(update.channel)
                    progress = 0f
                    downloadedBytes = 0L
                    downloadJob = updater.download(
                        update = update,
                        onProgress = { downloaded, total ->
                            downloadedBytes = downloaded
                            progress = if (total > 0L) {
                                (downloaded.toFloat() / total.toFloat()) * 100f
                            } else {
                                -1f 
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
                    updater.ignoreVersion(update.channel, update.versionName)
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
        
        LaunchedEffect(apk) { readyToInstall = null }
    } else if (apk != null && activity != null) {
        
        
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
