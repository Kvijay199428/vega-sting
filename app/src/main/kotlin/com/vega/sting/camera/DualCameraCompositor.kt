package com.vega.sting.camera

import android.content.Context

class DualCameraCompositor(private val context: Context) {
    fun initialize(config: DualCameraConfig): Boolean = true
    fun renderFrame() {}
    fun release() {}
}
