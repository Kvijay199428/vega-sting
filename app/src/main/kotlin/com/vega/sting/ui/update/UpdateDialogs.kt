package com.vega.sting.ui.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vega.sting.R
import com.vega.sting.ui.theme.AccentOrange
import com.vega.sting.ui.theme.Border
import com.vega.sting.ui.theme.Panel
import com.vega.sting.ui.theme.Success
import com.vega.sting.ui.theme.TextPrimary
import com.vega.sting.ui.theme.TextSecondary
import com.vega.sting.updater.ApkArtifact
import com.vega.sting.updater.UpdateChannel
import com.vega.sting.updater.UpdateChecker

/**
 * Compose Material3 dialogs for the in-app OTA updater, styled to match the
 * app's existing black / orange / monospace terminal theme.
 */

/** Shown when the manual check finds nothing newer for the selected channel. */
@Composable
fun UpToDateDialog(
    channelLabel: String,
    installedChannelLabel: String,
    installedVersionName: String,
    installedVersionCode: Long,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Text(
                text = stringResource(R.string.update_up_to_date_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Text(
                text = "You already have the latest $channelLabel build.\n" +
                    "Installed: $installedChannelLabel $installedVersionName (build $installedVersionCode).",
                fontFamily = FontFamily.Monospace
            )
        },
        confirmButton = {
            TerminalTextButton(text = stringResource(R.string.update_close), onClick = onDismiss)
        }
    )
}

/**
 * Shown when the user switches to a channel whose newest release is older than
 * the installed build. [reason] explains that Android cannot downgrade and that
 * the channel preference is kept until a newer release is published.
 */
@Composable
fun UpdateChannelSwitchBlockedDialog(reason: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Text(
                text = stringResource(R.string.update_channel_blocked_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Text(
                text = reason,
                fontFamily = FontFamily.Monospace
            )
        },
        confirmButton = {
            TerminalTextButton(text = stringResource(R.string.update_close), onClick = onDismiss)
        }
    )
}

/**
 * Shown on the next launch after a recorded install attempt by an older build
 * never took effect. Android refuses to replace a newer app with an older one,
 * and `ACTION_VIEW` gives no result callback to detect that at the time.
 */
@Composable
fun UpdateInstallBlockedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Text(
                text = stringResource(R.string.update_install_blocked_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Text(
                text = stringResource(R.string.update_install_blocked_body),
                fontFamily = FontFamily.Monospace
            )
        },
        confirmButton = {
            TerminalTextButton(text = stringResource(R.string.update_close), onClick = onDismiss)
        }
    )
}

/** Shown when a candidate release exists but cannot replace this installation. */
@Composable
fun UpdateIncompatibleDialog(reason: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Text(
                text = stringResource(R.string.update_incompatible_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Text(
                text = reason.ifBlank { stringResource(R.string.update_incompatible_body) },
                fontFamily = FontFamily.Monospace
            )
        },
        confirmButton = {
            TerminalTextButton(text = stringResource(R.string.update_close), onClick = onDismiss)
        }
    )
}

/** Shown when the manual check cannot reach the API. */
@Composable
fun UpdateCheckFailedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Text(
                text = stringResource(R.string.update_check_failed_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Text(
                text = stringResource(R.string.update_check_failed),
                fontFamily = FontFamily.Monospace
            )
        },
        confirmButton = {
            TerminalTextButton(text = stringResource(R.string.update_close), onClick = onDismiss)
        }
    )
}

/** Shown while an update check is in flight. */
@Composable
fun CheckingDialog() {
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

/** Shown when the APK download fails; carries the underlying reason. */
@Composable
fun UpdateDownloadFailedDialog(
    reason: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Text(
                text = "Download Failed",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Text(
                text = stringResource(R.string.update_download_error, reason),
                fontFamily = FontFamily.Monospace
            )
        },
        confirmButton = {
            TerminalTextButton(text = stringResource(R.string.update_try_again), onClick = onRetry)
        },
        dismissButton = {
            TerminalTextButton(
                text = stringResource(R.string.update_close),
                onClick = onDismiss,
                contentColor = TextSecondary
            )
        }
    )
}

/**
 * Shown when there is not enough free space to download the release. Offers a
 * retry (which re-checks free space for the same release) and a fresh update
 * check; both keep the currently selected channel and target release.
 */
@Composable
fun UpdateInsufficientStorageDialog(
    requiredBytes: Long,
    availableBytes: Long,
    onRetry: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Text(
                text = stringResource(R.string.update_insufficient_storage_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.update_insufficient_storage_body),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Required: ${formatBytes(requiredBytes)}\n" +
                        "Available: ${formatBytes(availableBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TerminalTextButton(text = stringResource(R.string.update_try_download_again), onClick = onRetry)
        },
        dismissButton = {
            Row {
                TerminalTextButton(
                    text = stringResource(R.string.update_check_for_updates),
                    onClick = onCheckForUpdates
                )
                Spacer(Modifier.width(8.dp))
                TerminalTextButton(
                    text = stringResource(R.string.update_close),
                    onClick = onCancel,
                    contentColor = TextSecondary
                )
            }
        }
    )
}

/**
 * Shown when the selected channel's newest valid release is OLDER than the
 * installed build. Android will not replace a newer app with an older one, so
 * this explains the manual uninstall/reinstall path instead of offering an
 * ordinary in-place update.
 */
@Composable
fun ManualDowngradeDialog(
    update: UpdateChecker.UpdateInfo,
    installedChannel: UpdateChannel,
    installedVersionName: String,
    installedVersionCode: Long,
    onDownload: () -> Unit,
    onWait: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Column {
                Text(
                    text = stringResource(R.string.update_channel_switch_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${update.channel.label} ${update.versionName}  (build ${update.versionCode})",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentOrange,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Currently installed: ${installedChannel.label} $installedVersionName " +
                        "(build $installedVersionCode)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.update_downgrade_body),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.update_downgrade_data_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentOrange,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TerminalTextButton(text = stringResource(R.string.update_download_manual), onClick = onDownload)
        },
        dismissButton = {
            Row {
                TerminalTextButton(
                    text = stringResource(R.string.update_wait_for_newer),
                    onClick = onWait,
                    contentColor = TextSecondary
                )
                Spacer(Modifier.width(8.dp))
                TerminalTextButton(
                    text = stringResource(R.string.update_close),
                    onClick = onCancel,
                    contentColor = TextSecondary
                )
            }
        }
    )
}

/**
 * Shown after an older (manual-downgrade) APK has been downloaded and verified.
 * Explains that the user must uninstall the current app first, then install the
 * saved APK from the public download folder.
 */
@Composable
fun ManualDowngradeReadyDialog(
    update: UpdateChecker.UpdateInfo,
    artifact: ApkArtifact,
    onDone: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Text(
                text = stringResource(R.string.update_manual_install_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "${update.channel.label} ${update.versionName} (build ${update.versionCode}) " +
                        "was saved.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.update_manual_install_steps, ApkStoreFolderLabel),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Saved as: ${artifact.displayName}\nFolder: $ApkStoreFolderLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TerminalTextButton(text = stringResource(R.string.update_close), onClick = onDone)
        }
    )
}

/** Human-visible public download folder, e.g. "Download/VEGA-STING/". */
private const val ApkStoreFolderLabel = "Download/VEGA-STING/"

/**
 * Shown once the download completes, before handing off to the system installer.
 */
@Composable
fun UpdateReadyToInstallDialog(
    update: UpdateChecker.UpdateInfo,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Column {
                Text(
                    text = stringResource(R.string.update_install_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${update.channel.label} ${update.versionName}  (build ${update.versionCode})",
                    style = MaterialTheme.typography.bodySmall,
                    color = Success,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        text = {
            Text(
                text = stringResource(R.string.update_install_body),
                fontFamily = FontFamily.Monospace
            )
        },
        confirmButton = {
            TerminalTextButton(text = stringResource(R.string.update_install_now), onClick = onInstall)
        },
        dismissButton = {
            TerminalTextButton(
                text = stringResource(R.string.update_later),
                onClick = onDismiss,
                contentColor = TextSecondary
            )
        }
    )
}

/**
 * Shown when a newer release is available.
 *
 * @param onDownload begin the download; the dialog then switches to the progress state
 * @param onSkip ignore this version (not prompted again until a newer one ships)
 * @param onLater dismiss without ignoring (prompted again on the next check)
 */
@Composable
fun UpdateAvailableDialog(
    update: UpdateChecker.UpdateInfo,
    installedChannel: UpdateChannel,
    installedVersionName: String,
    installedVersionCode: Long,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Column {
                Text(
                    text = stringResource(R.string.update_available_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${update.channel.label} ${update.versionName}  (build ${update.versionCode})",
                    style = MaterialTheme.typography.bodySmall,
                    color = Success,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Installed: ${installedChannel.label} $installedVersionName (build $installedVersionCode)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                if (update.channel != installedChannel) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (update.channel == UpdateChannel.DEBUG) {
                            "You are switching to the DEBUG channel. DEBUG builds may contain " +
                                "unfinished features and will replace your current installation."
                        } else {
                            "You are switching to the STABLE channel."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentOrange,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (update.versionCode < installedVersionCode) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.update_older_build_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentOrange,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${update.assetName}  (${formatBytes(update.assetSizeBytes)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                if (!update.notes.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.update_notes_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentOrange,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = update.notes,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            TerminalTextButton(text = stringResource(R.string.update_install_now), onClick = onDownload)
        },
        dismissButton = {
            Row {
                TerminalTextButton(
                    text = stringResource(R.string.update_skip),
                    onClick = onSkip,
                    contentColor = TextSecondary
                )
                Spacer(Modifier.width(8.dp))
                TerminalTextButton(
                    text = stringResource(R.string.update_later),
                    onClick = onLater
                )
            }
        }
    )
}

/**
 * Shown while the APK is downloading.
 *
 * @param progress 0..100, or negative when the server sends no Content-Length
 * @param downloadedBytes bytes fetched so far, shown when [progress] is unknown
 */
@Composable
fun UpdateDownloadProgressDialog(
    progress: Float,
    downloadedBytes: Long,
    onCancel: () -> Unit
) {
    AlertDialog(
        // Blocking while downloading: cancelling is explicit via the button below.
        onDismissRequest = { },
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Text(
                text = "Downloading update",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column {
                if (progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { (progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = AccentOrange,
                        trackColor = Border
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentOrange)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = if (progress >= 0f) {
                        stringResource(R.string.update_downloading, progress.toInt())
                    } else {
                        stringResource(
                            R.string.update_downloading_unknown,
                            formatBytes(downloadedBytes)
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TerminalTextButton(
                text = "Cancel",
                onClick = onCancel,
                contentColor = TextSecondary
            )
        }
    )
}

/** Shown when Android blocks the install until "Install unknown apps" is granted. */
@Composable
fun UpdatePermissionRequiredDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        titleContentColor = AccentOrange,
        textContentColor = TextPrimary,
        shape = RoundedCornerShape(4.dp),
        title = {
            Text(
                text = stringResource(R.string.update_allow_install_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Text(
                text = stringResource(R.string.update_allow_install_body),
                fontFamily = FontFamily.Monospace
            )
        },
        confirmButton = {
            TerminalTextButton(
                text = stringResource(R.string.update_open_settings),
                onClick = onOpenSettings
            )
        },
        dismissButton = {
            TerminalTextButton(
                text = stringResource(R.string.update_close),
                onClick = onDismiss,
                contentColor = TextSecondary
            )
        }
    )
}

/** Terminal-styled text button used by every dialog above. */
@Composable
private fun TerminalTextButton(
    text: String,
    onClick: () -> Unit,
    contentColor: androidx.compose.ui.graphics.Color = AccentOrange
) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, contentColor),
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            contentColor = contentColor
        )
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** Human-readable byte size, e.g. "18.4 MB". */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "unknown size"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
