package com.vega.sting.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vega.sting.ui.theme.*

@Composable
fun StatusPanel(
    mode: String? = null,
    storageLocation: String,
    freeSpace: String,
    codec: String,
    resolution: String,
    fps: String,
    timer: String? = null,
    fileName: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel)
            .border(0.5.dp, Border)
            .padding(8.dp)
    ) {
        if (mode != null) {
            StatusItem(label = "MODE", value = mode, color = AccentOrange)
            Spacer(modifier = Modifier.height(4.dp))
        }
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusItem(label = "STORAGE", value = storageLocation)
            StatusItem(label = "FREE", value = freeSpace)
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusItem(label = "CODEC", value = codec)
            StatusItem(label = "RES", value = resolution)
            StatusItem(label = "FPS", value = fps)
        }

        if (fileName != null) {
            Spacer(modifier = Modifier.height(4.dp))
            StatusItem(label = "FILE", value = fileName)
        }

        if (timer != null) {
            Spacer(modifier = Modifier.height(4.dp))
            StatusItem(label = "TIMER", value = timer, color = AccentYellow)
        }
    }
}

@Composable
private fun StatusItem(label: String, value: String, color: androidx.compose.ui.graphics.Color = TextPrimary) {
    Row {
        Text(text = "$label: ", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(text = value.uppercase(), style = MaterialTheme.typography.bodySmall, color = color)
    }
}
