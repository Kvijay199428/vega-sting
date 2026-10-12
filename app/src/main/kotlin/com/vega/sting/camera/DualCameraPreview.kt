package com.vega.sting.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun DualCameraPreview(
    config: DualCameraConfig = DualCameraConfig(),
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text("Live stacked preview (illustration)")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color(0xFF1E1E1E))
        ) {
            if (config.layout == DualCameraLayout.PORTRAIT_STACK) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF3B82F6)),
                        contentAlignment = Alignment.Center
                    ) { Text("FRONT", color = Color.White) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF10B981)),
                        contentAlignment = Alignment.Center
                    ) { Text("BACK", color = Color.White) }
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color(0xFF3B82F6)),
                        contentAlignment = Alignment.Center
                    ) { Text("FRONT", color = Color.White) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color(0xFF10B981)),
                        contentAlignment = Alignment.Center
                    ) { Text("BACK", color = Color.White) }
                }
            }
        }
    }
}
