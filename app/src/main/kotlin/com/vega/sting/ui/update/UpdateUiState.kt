package com.vega.sting.ui.update

import com.vega.sting.updater.ApkArtifact
import com.vega.sting.updater.UpdateChecker

/**
 * The single authoritative state of the in-app updater.
 *
 * Exactly one dialog is rendered for the active state, which structurally
 * prevents two update dialogs (e.g. "update available" and "ready to install")
 * from being shown at once for the same release. The update prompt is *consumed*
 * when a download starts: the state moves to [Downloading] and the original
 * [UpdateAvailable] value is gone, so it can never reappear behind the next
 * dialog.
 */
sealed interface UpdateUiState {

    /** Nothing to show. */
    data object Idle : UpdateUiState

    /** A background or manual check is running. */
    data object Checking : UpdateUiState

    /** The check could not reach the release API. */
    data object CheckFailed : UpdateUiState

    /** A newer, ordinary (in-place) update is available. */
    data class UpdateAvailable(val update: UpdateChecker.UpdateInfo) : UpdateUiState

    /** The selected channel's newest build is older than the installed one. */
    data class ManualDowngrade(val update: UpdateChecker.UpdateInfo) : UpdateUiState

    /** An APK is downloading. [progress] is 0..100, or negative when unknown. */
    data class Downloading(
        val update: UpdateChecker.UpdateInfo,
        val progress: Float,
        val downloadedBytes: Long
    ) : UpdateUiState

    /** Not enough free space to download the release. */
    data class InsufficientStorage(
        val update: UpdateChecker.UpdateInfo,
        val requiredBytes: Long,
        val availableBytes: Long
    ) : UpdateUiState

    /** The download or its verification failed. */
    data class DownloadFailed(val update: UpdateChecker.UpdateInfo, val reason: String) : UpdateUiState

    /** A verified APK is ready to hand to the system installer. */
    data class ReadyToInstall(val update: UpdateChecker.UpdateInfo, val artifact: ApkArtifact) : UpdateUiState

    /** A verified *older* APK is ready; the user must uninstall first. */
    data class ManualDowngradeReady(val update: UpdateChecker.UpdateInfo, val artifact: ApkArtifact) : UpdateUiState

    /** Installation was launched; the outcome is confirmed on the next launch. */
    data class AwaitingInstaller(val update: UpdateChecker.UpdateInfo) : UpdateUiState

    /** The user must grant "Install unknown apps" before installation can start. */
    data class PermissionRequired(val update: UpdateChecker.UpdateInfo, val artifact: ApkArtifact) : UpdateUiState

    /** A recorded install launch was refused (typically a downgrade). */
    data object InstallBlocked : UpdateUiState

    /** The manual check found nothing newer for the selected channel. */
    data class UpToDate(
        val channelLabel: String,
        val installedChannelLabel: String,
        val installedVersionName: String,
        val installedVersionCode: Long
    ) : UpdateUiState

    /** The selected channel has published no usable release yet. */
    data class ChannelAhead(val reason: String) : UpdateUiState

    /** Releases exist for the channel but none can replace this install. */
    data class Incompatible(val reason: String) : UpdateUiState
}
