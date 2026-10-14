package com.vega.sting.camera

import android.content.Context
import java.io.File

class DualRecordingPipeline(private val context: Context) {
    fun start(config: DualCameraConfig, outputFile: File): Boolean = true
    fun stop(): Boolean = true
    fun release() {}
}
