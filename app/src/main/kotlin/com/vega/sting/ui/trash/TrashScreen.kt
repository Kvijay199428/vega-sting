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
import androidx.compose.ui.unit.dp
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

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("WARNING", color = AccentYellow) },
            text = { Text("DELETE ${selectedIds.size} RECORDINGS PERMANENTLY? THIS CANNOT BE UNDONE.", color = TextPrimary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedRecordings =
                            recordings.filter {
                                selectedIds.contains(it.id)
                            }
            
                        selectedRecordings.forEach {
                            viewModel.permanentDelete(it)
                        }
            
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
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "TRASH BIN", style = MaterialTheme.typography.headlineMedium)
            Text(text = "AUTO DELETE: 30 DAYS", style = MaterialTheme.typography.bodySmall, color = AccentYellow)
        }

        // Action Bar (Top)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val allSelected =
                recordings.isNotEmpty() &&
                recordings.all {
                    selectedIds.contains(it.id)
                }
                
            TerminalButton(
                text = if (allSelected) "CLEAR ALL" else "SELECT ALL", 
                onClick = { 
                    if (allSelected) {
                        viewModel.clearSelection()
                    } else {
                        viewModel.selectAll(recordings.map { it.id })
                    }
                }, 
                modifier = Modifier.weight(1f)
            )
            TerminalButton(text = "CLEAR SEL", onClick = { viewModel.clearSelection() }, modifier = Modifier.weight(1f))
        }

        // Table Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(vertical = 4.dp, horizontal = 8.dp)
                .border(0.5.dp, Border)
        ) {
            Text(text = "#", modifier = Modifier.weight(0.1f), style = MaterialTheme.typography.bodySmall, color = AccentYellow)
            Text(text = "NAME", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall, color = AccentYellow)
            Text(text = "DATE", modifier = Modifier.weight(0.3f), style = MaterialTheme.typography.bodySmall, color = AccentYellow)
            Text(text = "SEL", modifier = Modifier.weight(0.1f), style = MaterialTheme.typography.bodySmall, color = AccentYellow)
        }

        // Trash List
        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(recordings) { index, recording ->
                TrashRow(
                    index = index + 1,
                    recording = recording,
                    isSelected = selectedIds.contains(recording.id),
                    onToggle = { viewModel.toggleSelection(recording.id) }
                )
            }
        }

        // Footer Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 12.dp
                ),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (selectedIds.isNotEmpty()) {
                TerminalButton(text = "RESTORE", onClick = {
                    selectedIds.forEach { viewModel.restore(it) }
                    viewModel.clearSelection()
                }, modifier = Modifier.weight(1f))
                TerminalButton(text = "DELETE", onClick = { showDeleteDialog = true }, isStopMode = true, modifier = Modifier.weight(1f))
            }
            TerminalButton(text = "BACK", onClick = onBack, modifier = Modifier.weight(1f))
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
    val dateStr = sdf.format(Date(recording.timestamp))

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
        
        Row(modifier = Modifier.weight(0.5f), verticalAlignment = Alignment.CenterVertically) {
            Text(text = if (isAudio) "🎤" else "🎥", modifier = Modifier.padding(end = 4.dp))
            Text(text = recording.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        
        Text(text = dateStr, modifier = Modifier.weight(0.3f), style = MaterialTheme.typography.bodySmall)
        
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.weight(0.1f).size(18.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = AccentOrange,
                uncheckedColor = Border,
                checkmarkColor = Background
            )
        )
    }
}
