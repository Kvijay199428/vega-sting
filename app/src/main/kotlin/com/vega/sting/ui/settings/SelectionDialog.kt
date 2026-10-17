package com.vega.sting.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vega.sting.ui.theme.AccentOrange
import com.vega.sting.ui.theme.AccentYellow
import com.vega.sting.ui.theme.Panel
import com.vega.sting.ui.theme.TextSecondary

/**
 * One entry in a [SelectionDialog]: [value] is persisted when the row is chosen,
 * [label] is drawn on the row, and [detail] is the optional right-hand text.
 * When [detail] is null a selected row shows "SET" and every other row shows
 * nothing.
 */
data class SelectionOption<T>(
    val value: T,
    val label: String,
    val detail: String? = null
)

/**
 * The single list-picker shared by every multi-choice Settings row and by the
 * dual-camera block, so all such dialogs look and behave identically.
 *
 * The option list is scrollable so long lists stay reachable on short screens.
 */
@Composable
fun <T> SelectionDialog(
    title: String,
    options: List<SelectionOption<T>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    footnote: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = AccentYellow) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    SettingsItem(
                        label = option.label,
                        value = option.detail ?: if (option.value == selected) "SET" else "",
                        onClick = { onSelect(option.value) }
                    )
                }
                if (footnote != null) {
                    Text(
                        text = footnote,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE", color = AccentOrange) }
        },
        containerColor = Panel,
        shape = MaterialTheme.shapes.extraSmall
    )
}
