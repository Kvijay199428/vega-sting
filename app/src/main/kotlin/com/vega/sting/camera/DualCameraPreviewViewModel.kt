package com.vega.sting.camera

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DualCameraPreviewViewModel(private val context: Context) : ViewModel() {
    private val _config = MutableStateFlow(DualCameraConfig())
    val config: StateFlow<DualCameraConfig> = _config.asStateFlow()

    private val _capability = MutableStateFlow(DualCameraCapabilityResult())
    val capability: StateFlow<DualCameraCapabilityResult> = _capability.asStateFlow()

    fun updateConfig(newConfig: DualCameraConfig) {
        _config.value = newConfig
    }

    fun checkCapability() {
        val checker = DualCameraCapabilityChecker(context)
        _capability.value = checker.check()
    }

    override fun onCleared() {
        super.onCleared()
    }
}
