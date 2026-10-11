package com.vega.sting.ui.dialogs

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.vega.sting.ui.theme.*


@Composable
fun LowStorageDialog(
    freeSpace: String,
    onContinueVideo: () -> Unit,
    onSwitchAudio: () -> Unit,
    onCancel: () -> Unit = onContinueVideo
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                "LOW STORAGE",
                color = AccentYellow
            )
        },
        text = {
            Text(
                "ONLY $freeSpace REMAINING.\n\n" +
                "DELETE OLD RECORDINGS OR BACKUP RECORDINGS.\n\n" +
                "SWITCHING TO AUDIO WILL USE FAR LESS SPACE.",
                color = TextPrimary
            )
        },
        confirmButton = {
            TextButton(
                onClick = onSwitchAudio
            ) {
                Text(
                    "SWITCH AUDIO",
                    color = AccentOrange
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onCancel
                ) {
                    Text(
                        "CANCEL",
                        color = TextSecondary
                    )
                }
                TextButton(
                    onClick = onContinueVideo
                ) {
                    Text(
                        "RECORD VIDEO ANYWAY",
                        color = Error
                    )
                }
            }
        },
        containerColor = Panel
    )
}

/**
 * Dismissible startup warning shown when the selected recording destination is
 * getting low (but still above the hard reserve). It never blocks recording on
 * its own - the service is the authority - it only informs.
 */
@Composable
fun StorageWarningDialog(
    freeSpace: String,
    reserve: String,
    destination: String,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Low storage space", color = AccentYellow)
        },
        text = {
            Text(
                "Your recording storage ($destination) is almost full.\n\n" +
                    "Only $freeSpace free. $reserve is kept as the minimum reserve, " +
                    "after which recording stops to protect your files.\n\n" +
                    "Clear some space to prevent recording from stopping unexpectedly.",
                color = TextPrimary
            )
        },
        confirmButton = {
            TextButton(onClick = onManage) {
                Text("MANAGE RECORDINGS", color = AccentOrange)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("DISMISS", color = TextSecondary)
            }
        },
        containerColor = Panel
    )
}
