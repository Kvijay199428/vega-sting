package com.vega.sting.ui.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vega.sting.R
import com.vega.sting.ui.components.DetailHeaderBar
import com.vega.sting.ui.components.TerminalButton
import com.vega.sting.ui.theme.*
import com.vega.sting.ui.viewmodel.RecordingViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TrashScreen(viewModel: RecordingViewModel, onBack: () -> Unit) {
    val recordings by viewModel.trashRecordings.collectAsState(initial = emptyList())
    val selectedIds by viewModel.selectedIds.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }

    val allSelected = recordings.isNotEmpty() && recordings.all { selectedIds.contains(it.id) }

    
    
    
    val selectedCount = selectedIds.count { id -> recordings.any { it.id == id } }
    val hasSelection = selectedCount > 0

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("WARNING", color = AccentYellow) },
            text = {
                Text(
                    "DELETE $selectedCount RECORDINGS PERMANENTLY? THIS CANNOT BE UNDONE.",
                    color = TextPrimary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        recordings.filter { selectedIds.contains(it.id) }
                            .forEach { viewModel.permanentDelete(it) }
                        viewModel.clearSelection()
                        showDeleteDialog = false
                    }
                ) {
                    Text("DELETE", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("CANCEL", color = TextSecondary)
                }
            },
            containerColor = Panel,
            shape = MaterialTheme.shapes.extraSmall
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
    ) {
        DetailHeaderBar(
            title = "TRASH",
            onBack = onBack,
            trailing = {
                Text(
                    text = "AUTO DELETE: 30 DAYS",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentYellow
                )
            }
        )

        
        
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(vertical = 4.dp, horizontal = 8.dp)
                .border(0.5.dp, Border)
        ) {
            Text(text = "#", modifier = Modifier.weight(0.1f), style = MaterialTheme.typography.bodySmall, color = AccentYellow)
            Text(text = "NAME", modifier = Modifier.weight(0.55f), style = MaterialTheme.typography.bodySmall, color = AccentYellow)
            Text(text = "DELETED", modifier = Modifier.weight(0.25f), style = MaterialTheme.typography.bodySmall, color = AccentYellow)
            Text(
                text = if (allSelected) "NONE" else "ALL",
                modifier = Modifier
                    .weight(0.1f)
                    .heightIn(min = 48.dp)
                    .clickable(enabled = recordings.isNotEmpty()) {
                        if (allSelected) viewModel.clearSelection()
                        else viewModel.selectAll(recordings.map { it.id })
                    }
                    .semantics {
                        contentDescription = if (allSelected) {
                            "Clear all trash selections"
                        } else {
                            "Select all ${recordings.size} trashed recordings"
                        }
                    },
                style = MaterialTheme.typography.bodySmall,
                color = if (recordings.isEmpty()) TextSecondary else AccentYellow
            )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(
                items = recordings,
                key = { _, recording -> recording.id }
            ) { index, recording ->
                TrashRow(
                    index = index + 1,
                    recording = recording,
                    isSelected = selectedIds.contains(recording.id),
                    onToggle = { viewModel.toggleSelection(recording.id) }
                )
            }
        }

        
        
        
        if (hasSelection) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TerminalButton(
                    text = "RESTORE",
                    onClick = {
                        recordings.filter { selectedIds.contains(it.id) }
                            .forEach { viewModel.restore(it.id) }
                        viewModel.clearSelection()
                    },
                    modifier = Modifier.weight(1f)
                )
                TerminalButton(
                    text = "DELETE",
                    onClick = { showDeleteDialog = true },
                    isStopMode = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun TrashRow(
    index: Int,
    recording: com.vega.sting.database.Recording,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    
    
    val dateStr = recording.deletedAt?.let { sdf.format(Date(it)) } ?: "—"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp)
            .border(0.5.dp, if (isSelected) AccentOrange else Border)
            .background(if (isSelected) Panel else Background)
            .clickable { onToggle() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isAudio = recording.type == com.vega.sting.database.RecordingType.AUDIO
        Text(text = index.toString(), modifier = Modifier.weight(0.1f), style = MaterialTheme.typography.bodySmall)

        Row(modifier = Modifier.weight(0.55f), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(if (isAudio) R.drawable.ic_mic else R.drawable.ic_videocam),
                contentDescription = null,
                tint = if (isSelected) AccentOrange else TextSecondary,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(16.dp)
            )
            Text(text = recording.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }

        Text(text = dateStr, modifier = Modifier.weight(0.25f), style = MaterialTheme.typography.bodySmall)

        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            modifier = Modifier
                .weight(0.1f)
                
                
                
                .semantics { contentDescription = "Select ${recording.name}" },
            colors = CheckboxDefaults.colors(
                checkedColor = AccentOrange,
                uncheckedColor = Border,
                checkmarkColor = Background
            )
        )
    }
}
