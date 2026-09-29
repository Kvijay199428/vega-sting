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
import com.vega.sting.updater.UpdateChecker

/**
 * Compose Material3 dialogs for the in-app OTA updater, styled to match the
 * app's existing black / orange / monospace terminal theme.
 */

/** Shown when the manual check finds nothing newer. */
@Composable
fun UpToDateDialog(onDismiss: () -> Unit) {
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
                text = "You already have the latest version.",
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

/** Shown when the APK download fails; carries the underlying reason. */
@Composable
fun UpdateDownloadFailedDialog(reason: String, onDismiss: () -> Unit) {
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
            TerminalTextButton(text = stringResource(R.string.update_close), onClick = onDismiss)
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
    currentVersion: String,
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
                    text = "${update.versionName}  →  current $currentVersion",
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

/** Shown once the download completes, before handing off to the system installer. */
@Composable
fun UpdateReadyToInstallDialog(
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
            Text(
                text = stringResource(R.string.update_install_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
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
