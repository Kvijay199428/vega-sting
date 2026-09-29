package com.vega.sting.shortcuts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.vega.sting.core.RecordingStateManager
import com.vega.sting.services.RecordingService
import com.vega.sting.services.ServiceUtils
import com.vega.sting.settings.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PowerToggleRecordingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        lifecycleScope.launch {
            toggleRecording()
            // Close the activity immediately
            finish()
        }
    }

    private suspend fun toggleRecording() {
        val isRecording = RecordingStateManager.isRecording()
        
        if (isRecording) {
            // Stop recording using ACTION_STOP
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
            startForegroundService(intent)
        } else {
            // Start recording based on user preference using ACTION_START_VIDEO/AUDIO
            val settingsManager = SettingsManager(this)
            val mode = settingsManager.powerButtonMode.first()
            
            val intent = Intent(this, RecordingService::class.java).apply {
                action = if (mode == "AUDIO") RecordingService.ACTION_START_AUDIO else RecordingService.ACTION_START_VIDEO
            }
            startForegroundService(intent)
        }
    }
}
