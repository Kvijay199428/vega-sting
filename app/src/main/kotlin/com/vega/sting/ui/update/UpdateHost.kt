package com.vega.sting.ui.update

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vega.sting.BuildConfig
import com.vega.sting.core.RecordingStateManager
import com.vega.sting.services.UpdateCheckBus
import com.vega.sting.services.UpdateCheckService
import com.vega.sting.settings.SettingsManager
import com.vega.sting.updater.ApkArtifact
import com.vega.sting.updater.InstallController
import com.vega.sting.updater.UpdateChannel
import com.vega.sting.updater.UpdateChecker
import com.vega.sting.updater.UpdaterManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


/**
 * Hosts the OTA update / download / install flow behind a single [UpdateUiState]
 * state machine. At most one dialog is shown at any time, and an update prompt is
 * consumed as soon as its download starts, so it can never reappear behind the
 * progress or install dialog for the same release.
 */
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

    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var downloadJob by remember { mutableStateOf<UpdaterManager.DownloadJob?>(null) }

    // Increments on every new download attempt. A result whose token no longer
    // matches is from a cancelled or superseded job and is ignored, so a late
    // callback can never clobber the current state.
    var downloadToken by remember { mutableIntStateOf(0) }

    // An update found while a recording is in progress is held here and offered
    // once the recording stops.
    var deferredUpdate by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    /** Reports a launched install whose effect could not be confirmed (downgrade). */
    fun reportUnconfirmedInstall() {
        val pending = updater.pendingInstallVersionCode() ?: return
        updater.clearPendingInstall()
        if (BuildConfig.VERSION_CODE.toLong() != pending) {
            state = UpdateUiState.InstallBlocked
        }
    }

    fun presentUpdate(update: UpdateChecker.UpdateInfo) {
        state = if (update.versionCode < BuildConfig.VERSION_CODE.toLong()) {
            // The target is older than the installed build: Android will not
            // install it in place. Offer the manual downgrade workflow instead
            // of an ordinary update prompt.
            UpdateUiState.ManualDowngrade(update)
        } else {
            UpdateUiState.UpdateAvailable(update)
        }
    }

    fun startCheck(manual: Boolean) {
        if (state == UpdateUiState.Checking) return
        if (!manual && RecordingStateManager.isBusy()) return

        // Only a user-initiated check may show the blocking "Checking for
        // updates" dialog. Automatic checks run out of process (see
        // UpdateCheckService) and never interrupt the launch.
        if (manual) state = UpdateUiState.Checking

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
                when (result) {
                    is UpdaterManager.CheckResult.UpdateAvailable -> {
                        if (RecordingStateManager.isBusy()) {
                            deferredUpdate = result.update
                            state = UpdateUiState.Idle
                        } else {
                            presentUpdate(result.update)
                        }
                    }
                    UpdaterManager.CheckResult.UpToDate -> state = if (manual) {
                        UpdateUiState.UpToDate(
                            channelLabel = channel.label,
                            installedChannelLabel = UpdateChannel.installed().label,
                            installedVersionName = BuildConfig.VERSION_NAME,
                            installedVersionCode = BuildConfig.VERSION_CODE.toLong()
                        )
                    } else {
                        UpdateUiState.Idle
                    }
                    is UpdaterManager.CheckResult.ChannelAhead -> state = if (manual) {
                        UpdateUiState.ChannelAhead(result.reason)
                    } else {
                        UpdateUiState.Idle
                    }
                    is UpdaterManager.CheckResult.Incompatible -> state = if (manual) {
                        UpdateUiState.Incompatible(result.reason)
                    } else {
                        UpdateUiState.Idle
                    }
                    is UpdaterManager.CheckResult.Failed -> state = if (manual) {
                        UpdateUiState.CheckFailed
                    } else {
                        UpdateUiState.Idle
                    }
                }
            }

            if (!started) state = UpdateUiState.Idle
        }
    }

    /** Begins (or ignores a duplicate) download of [update]. */
    fun beginDownload(update: UpdateChecker.UpdateInfo, allowDowngrade: Boolean) {
        // Duplicate-operation protection: never start a second job for the same
        // release while one is already running.
        if (downloadJob != null) return

        state = UpdateUiState.Downloading(update, progress = 0f, downloadedBytes = 0L)
        val token = ++downloadToken
        downloadJob = updater.download(
            update = update,
            allowDowngrade = allowDowngrade,
            onProgress = { downloaded, total ->
                if (token != downloadToken) return@download
                val pct = if (total > 0L) (downloaded.toFloat() / total.toFloat()) * 100f else -1f
                state = UpdateUiState.Downloading(update, progress = pct, downloadedBytes = downloaded)
            },
            onResult = { result ->
                if (token != downloadToken) return@download
                downloadJob = null
                when (result) {
                    is UpdaterManager.DownloadResult.Success -> {
                        state = if (allowDowngrade || update.versionCode < BuildConfig.VERSION_CODE.toLong()) {
                            UpdateUiState.ManualDowngradeReady(update, result.artifact)
                        } else {
                            UpdateUiState.ReadyToInstall(update, result.artifact)
                        }
                    }
                    is UpdaterManager.DownloadResult.InsufficientStorage ->
                        state = UpdateUiState.InsufficientStorage(update, result.requiredBytes, result.availableBytes)
                    is UpdaterManager.DownloadResult.Failed ->
                        state = UpdateUiState.DownloadFailed(update, result.reason)
                    UpdaterManager.DownloadResult.Cancelled ->
                        state = UpdateUiState.Idle
                }
            }
        )
    }

    /** Hands a verified artifact to the system Package Installer. */
    fun launchInstaller(update: UpdateChecker.UpdateInfo, artifact: ApkArtifact) {
        if (activity == null) {
            // No Activity to launch the installer from; keep the artifact and stop.
            updater.reconcileDownloads(keep = artifact)
            state = UpdateUiState.Idle
            return
        }
        if (!InstallController.canInstallApks(context)) {
            state = UpdateUiState.PermissionRequired(update, artifact)
            return
        }
        // A launch supersedes any earlier unconfirmed one.
        updater.clearPendingInstall()
        if (update.versionCode < BuildConfig.VERSION_CODE.toLong()) {
            // Downgrade attempt: Android may refuse it, and we can only tell on
            // the next launch.
            updater.markInstallLaunched(update.versionCode)
        }
        InstallController.startInstall(activity, artifact)
        // Keep the artifact until a successful install is confirmed on next launch.
        updater.reconcileDownloads(keep = artifact)
        state = UpdateUiState.AwaitingInstaller(update)
    }

    LaunchedEffect(autoCheckEnabled) {
        // The automatic launch check runs in a background service so the app is
        // usable immediately; it is still gated on consent (this host only
        // composes after the consent gate) and on being idle.
        if (autoCheckEnabled) UpdateCheckService.startCheck(context)
    }

    // A result published by the background check is offered once, exactly like
    // an in-process check result would be. Consuming the slot first means a
    // configuration change cannot re-present a dismissed prompt.
    LaunchedEffect(Unit) {
        UpdateCheckBus.pending.collect { update ->
            if (update != null) {
                UpdateCheckBus.consume()
                if (RecordingStateManager.isBusy()) {
                    deferredUpdate = update
                } else {
                    presentUpdate(update)
                }
            }
        }
    }

    // Report an install Android refused. `ACTION_VIEW` gives no result callback,
    // so if the running version still isn't the one we launched, the install
    // never took effect.
    LaunchedEffect(Unit) { reportUnconfirmedInstall() }

    LaunchedEffect(manualCheckToken) {
        if (manualCheckToken > 0) startCheck(manual = true)
    }

    LaunchedEffect(deferredUpdate) {
        if (deferredUpdate != null) {
            while (RecordingStateManager.isBusy()) {
                delay(1_000)
            }
            deferredUpdate?.let {
                presentUpdate(it)
                deferredUpdate = null
            }
        }
    }

    // ------------------------------------------------------------------
    // Render exactly one dialog for the active state.
    // ------------------------------------------------------------------
    when (val current = state) {
        UpdateUiState.Idle,
        is UpdateUiState.AwaitingInstaller -> Unit // no dialog

        UpdateUiState.Checking -> CheckingDialog()

        UpdateUiState.CheckFailed -> UpdateCheckFailedDialog(onDismiss = { state = UpdateUiState.Idle })

        is UpdateUiState.UpToDate -> UpToDateDialog(
            channelLabel = current.channelLabel,
            installedChannelLabel = current.installedChannelLabel,
            installedVersionName = current.installedVersionName,
            installedVersionCode = current.installedVersionCode,
            onDismiss = { state = UpdateUiState.Idle }
        )

        is UpdateUiState.ChannelAhead -> UpdateChannelSwitchBlockedDialog(
            reason = current.reason,
            onDismiss = { state = UpdateUiState.Idle }
        )

        is UpdateUiState.Incompatible -> UpdateIncompatibleDialog(
            reason = current.reason,
            onDismiss = { state = UpdateUiState.Idle }
        )

        is UpdateUiState.UpdateAvailable -> UpdateAvailableDialog(
            update = current.update,
            installedChannel = UpdateChannel.installed(),
            installedVersionName = BuildConfig.VERSION_NAME,
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            onDownload = {
                updater.clearIgnoredVersion(current.update.channel)
                beginDownload(current.update, allowDowngrade = false)
            },
            onSkip = {
                updater.ignoreVersion(current.update.channel, current.update.versionName)
                state = UpdateUiState.Idle
            },
            onLater = { state = UpdateUiState.Idle }
        )

        is UpdateUiState.ManualDowngrade -> ManualDowngradeDialog(
            update = current.update,
            installedChannel = UpdateChannel.installed(),
            installedVersionName = BuildConfig.VERSION_NAME,
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            onDownload = {
                updater.clearIgnoredVersion(current.update.channel)
                beginDownload(current.update, allowDowngrade = true)
            },
            onWait = { state = UpdateUiState.Idle },
            onCancel = { state = UpdateUiState.Idle }
        )

        is UpdateUiState.Downloading -> UpdateDownloadProgressDialog(
            progress = current.progress,
            downloadedBytes = current.downloadedBytes,
            onCancel = {
                downloadToken++
                downloadJob?.cancel()
                downloadJob = null
                state = UpdateUiState.Idle
            }
        )

        is UpdateUiState.InsufficientStorage -> UpdateInsufficientStorageDialog(
            requiredBytes = current.requiredBytes,
            availableBytes = current.availableBytes,
            onRetry = { beginDownload(current.update, allowDowngrade = current.update.versionCode < BuildConfig.VERSION_CODE.toLong()) },
            onCheckForUpdates = { startCheck(manual = true) },
            onCancel = { state = UpdateUiState.Idle }
        )

        is UpdateUiState.DownloadFailed -> UpdateDownloadFailedDialog(
            reason = current.reason,
            onRetry = { beginDownload(current.update, allowDowngrade = current.update.versionCode < BuildConfig.VERSION_CODE.toLong()) },
            onDismiss = { state = UpdateUiState.Idle }
        )

        is UpdateUiState.ReadyToInstall -> UpdateReadyToInstallDialog(
            update = current.update,
            onInstall = { launchInstaller(current.update, current.artifact) },
            onDismiss = {
                updater.reconcileDownloads(keep = current.artifact)
                state = UpdateUiState.Idle
            }
        )

        is UpdateUiState.ManualDowngradeReady -> ManualDowngradeReadyDialog(
            update = current.update,
            artifact = current.artifact,
            onDone = {
                updater.reconcileDownloads(keep = current.artifact)
                state = UpdateUiState.Idle
            }
        )

        is UpdateUiState.PermissionRequired -> UpdatePermissionRequiredDialog(
            onOpenSettings = { activity?.let { InstallController.openUnknownSourcesSettings(it) } },
            onDismiss = {
                updater.reconcileDownloads(keep = current.artifact)
                state = UpdateUiState.Idle
            }
        )

        UpdateUiState.InstallBlocked -> UpdateInstallBlockedDialog(onDismiss = { state = UpdateUiState.Idle })
    }
}
