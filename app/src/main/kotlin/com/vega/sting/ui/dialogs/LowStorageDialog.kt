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
