package com.vega.sting.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun DualCameraPreview(
    config: DualCameraConfig = DualCameraConfig(),
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = if (config.layout == DualCameraLayout.PORTRAIT_STACK) "Portrait Stack Preview" else "Landscape Split Preview",
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .background(Color(0xFF1E1E1E))
        ) {
            if (config.layout == DualCameraLayout.PORTRAIT_STACK || config.layout == DualCameraLayout.AUTO) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF3B82F6)),
                        contentAlignment = Alignment.Center
                    ) { 
                        Text(if (config.frontOnTop) "FRONT" else "BACK", color = Color.White) 
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF10B981)),
                        contentAlignment = Alignment.Center
                    ) { 
                        Text(if (config.frontOnTop) "BACK" else "FRONT", color = Color.White) 
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color(0xFF3B82F6)),
                        contentAlignment = Alignment.Center
                    ) { 
                        Text(if (config.frontOnLeft) "FRONT" else "BACK", color = Color.White) 
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color(0xFF10B981)),
                        contentAlignment = Alignment.Center
                    ) { 
                        Text(if (config.frontOnLeft) "BACK" else "FRONT", color = Color.White) 
                    }
                }
            }
        }
        Text(
            text = "Split: % / %",
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
