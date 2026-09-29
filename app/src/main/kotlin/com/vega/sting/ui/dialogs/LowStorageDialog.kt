package com.vega.sting.ui.dialogs

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.vega.sting.ui.theme.*

@Composable
fun LowStorageDialog(
    freeSpace: String,
    onContinueVideo: () -> Unit,
    onSwitchAudio: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
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
                "CONTINUE VIDEO RECORDING?",
                color = TextPrimary
            )
        },
        confirmButton = {
            TextButton(
                onClick = onContinueVideo
            ) {
                Text(
                    "CONTINUE VIDEO",
                    color = Error
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onSwitchAudio
            ) {
                Text(
                    "SWITCH AUDIO",
                    color = AccentOrange
                )
            }
        },
        containerColor = Panel
    )
}
