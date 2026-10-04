package com.vega.sting.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RecordingState {
    IDLE,
    STARTING_VIDEO,
    STARTING_AUDIO,
    RECORDING_VIDEO,
    RECORDING_AUDIO,
    SWITCHING_TO_VIDEO,
    SWITCHING_TO_AUDIO,
    STOPPING,
    ERROR
}

object RecordingStateManager {
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _currentFileName = MutableStateFlow<String?>(null)
    val currentFileName: StateFlow<String?> = _currentFileName.asStateFlow()

    private val _currentCodec = MutableStateFlow<String?>(null)
    val currentCodec: StateFlow<String?> = _currentCodec.asStateFlow()

    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val stateMutex = Mutex()

    fun updateState(newState: RecordingState, fileName: String? = null, codec: String? = null) {
        scope.launch {
            stateMutex.withLock {
                _state.value = newState
                if (fileName != null) _currentFileName.value = fileName
                if (codec != null) _currentCodec.value = codec

                if (newState == RecordingState.RECORDING_VIDEO || newState == RecordingState.RECORDING_AUDIO) {
                    startTimer()
                } else if (newState == RecordingState.IDLE || newState == RecordingState.ERROR) {
                    stopTimer()
                    if (newState == RecordingState.IDLE) {
                        _currentFileName.value = null
                        _currentCodec.value = null
                    }
                }
                
                if (newState != RecordingState.ERROR) {
                    _error.value = null
                }
            }
        }
    }

    private fun startTimer() {
        if (timerJob != null) return
        _duration.value = 0
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                _duration.value += 1
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    fun setError(message: String) {
        scope.launch {
            stateMutex.withLock {
                _error.value = message
                _state.value = RecordingState.ERROR
                stopTimer()
            }
        }
    }

    fun isRecording(): Boolean {
        val s = _state.value
        return s == RecordingState.RECORDING_VIDEO || 
               s == RecordingState.RECORDING_AUDIO ||
               s == RecordingState.SWITCHING_TO_VIDEO ||
               s == RecordingState.SWITCHING_TO_AUDIO
    }

    
    fun isBusy(state: RecordingState = _state.value): Boolean {
        return state != RecordingState.IDLE && state != RecordingState.ERROR
    }
}
