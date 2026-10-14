package com.vega.sting.camera

import android.content.Context

class DualCameraController(private val context: Context) {
    private var isRunning = false

    fun start(config: DualCameraConfig): Boolean {
        isRunning = true
        return true
    }

    fun stop() {
        isRunning = false
    }

    fun release() {
        isRunning = false
    }

    fun isActive(): Boolean = isRunning
}
