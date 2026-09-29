package com.vega.sting.services

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vega.sting.MainActivity
import com.vega.sting.core.RecordingState
import com.vega.sting.core.RecordingStateManager

class RecordingTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val isRecording = RecordingStateManager.isRecording()

        val intent = Intent(this, RecordingService::class.java).apply {
            action = if (isRecording) RecordingService.ACTION_STOP else RecordingService.ACTION_START_VIDEO
        }
        startForegroundService(intent)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        val recordingState = RecordingStateManager.state.value
        
        when (recordingState) {
            RecordingState.RECORDING_VIDEO -> {
                qsTile.state = Tile.STATE_ACTIVE
                qsTile.label = "STOP VIDEO"
            }
            RecordingState.RECORDING_AUDIO -> {
                qsTile.state = Tile.STATE_ACTIVE
                qsTile.label = "STOP AUDIO"
            }
            RecordingState.STARTING_VIDEO, RecordingState.STARTING_AUDIO -> {
                qsTile.state = Tile.STATE_ACTIVE
                qsTile.label = "STARTING..."
            }
            else -> {
                qsTile.state = Tile.STATE_INACTIVE
                qsTile.label = "START REC"
            }
        }
        qsTile.updateTile()
    }
}
