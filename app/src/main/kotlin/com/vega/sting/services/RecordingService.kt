package com.vega.sting.services

import android.app.*
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.vega.sting.MainActivity
import com.vega.sting.R
import com.vega.sting.core.RecordingState
import com.vega.sting.core.RecordingStateManager
import com.vega.sting.database.AppDatabase
import com.vega.sting.database.Recording
import com.vega.sting.database.RecordingType
import com.vega.sting.recording.ActiveRecordingSpec
import com.vega.sting.recording.AudioRecordingManager
import com.vega.sting.recording.RecordingManager
import com.vega.sting.settings.SettingsManager
import com.vega.sting.storage.StorageManager
import com.vega.sting.storage.StorageType
import com.vega.sting.widget.RecordingWidgetProvider
import com.vega.sting.widget.WidgetStateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.vega.sting.camera.DeviceProfileManager
import com.vega.sting.storage.StorageHealthManager
import java.io.File

class RecordingService : LifecycleService() {
    companion object {
        const val ACTION_START_VIDEO = "ACTION_START_VIDEO"
        const val ACTION_START_AUDIO = "ACTION_START_AUDIO"
        const val ACTION_STOP = "ACTION_STOP"
    }

    private val CHANNEL_ID = "RecordingServiceChannel"
    private lateinit var videoRecordingManager: RecordingManager
    private lateinit var audioRecordingManager: AudioRecordingManager
    private lateinit var db: AppDatabase
    private var recordingType = RecordingType.VIDEO
    private var currentOutputFile: File? = null
    private var isSwitching = false
    private var pendingRecordingType: RecordingType? = null
    private var storageMonitorJob: Job? = null
    private lateinit var profileManager: DeviceProfileManager

    
    
    private var activeVideoSpec: ActiveRecordingSpec? = null

    override fun onCreate() {
        super.onCreate()
        videoRecordingManager = RecordingManager(this)
        audioRecordingManager = AudioRecordingManager(this)
        db = AppDatabase.getDatabase(this)
        profileManager = DeviceProfileManager(this)
        createNotificationChannel()

        
        lifecycleScope.launch {
            try {
                profileManager.getOrScanProfile()
                
                val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                db.cameraSessionTelemetryDao().deleteOlderThan(thirtyDaysAgo)
            } catch (_: Exception) {}
        }
    }

    private var ignoreWarning = false
    private var foregroundStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        ignoreWarning = intent?.getBooleanExtra("IGNORE_STORAGE_WARNING", false) ?: false

        when (intent?.action) {
            ACTION_START_VIDEO -> handleStartAction(RecordingType.VIDEO)
            ACTION_START_AUDIO -> handleStartAction(RecordingType.AUDIO)
            ACTION_STOP -> handleStopAction()
            else -> {
                val typeStr = intent?.getStringExtra("EXTRA_TYPE") ?: "VIDEO"
                val type = if (typeStr == "AUDIO") RecordingType.AUDIO else RecordingType.VIDEO
                handleStartAction(type)
            }
        }

        return START_STICKY
    }

    private fun handleStartAction(newType: RecordingType) {
        if (RecordingStateManager.isRecording()) {
            if (newType != recordingType) {
                
                isSwitching = true
                pendingRecordingType = newType
                RecordingStateManager.updateState(
                    if (newType == RecordingType.AUDIO) RecordingState.SWITCHING_TO_AUDIO else RecordingState.SWITCHING_TO_VIDEO
                )
                WidgetStateManager.saveState(
                    this,
                    if (newType == RecordingType.AUDIO) "RECORDING_AUDIO" else "RECORDING_VIDEO"
                )
                
                
                stopCurrentRecording()
                
                
                if (recordingType == RecordingType.AUDIO) {
                    onCurrentRecordingFinalized()
                }
            }
        } else {
            isSwitching = false
            recordingType = newType
            RecordingStateManager.updateState(
                if (recordingType == RecordingType.AUDIO) RecordingState.STARTING_AUDIO else RecordingState.STARTING_VIDEO
            )
            WidgetStateManager.saveState(
                this,
                if (recordingType == RecordingType.AUDIO) "RECORDING_AUDIO" else "RECORDING_VIDEO"
            )
            val notification = createNotification("VEGA STING is recording ${recordingType.name}...")
            startForeground(1, notification)
            foregroundStarted = true
            
            
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val settingsManager = SettingsManager(this@RecordingService)
                lifecycleScope.launch {
                    val location = settingsManager.storageLocation.first()
                    val storageType = when(location) {
                        "SD_CARD" -> StorageType.SD_CARD
                        "OTG" -> StorageType.OTG
                        else -> StorageType.INTERNAL
                    }
                    val storageDir = StorageManager.getRecordingDirectory(this@RecordingService, storageType)
                    val freeBytes = StorageHealthManager.getFreeSpaceBytes(storageDir)

                    if (newType == RecordingType.VIDEO && freeBytes <= StorageHealthManager.WARNING_LEVEL && !ignoreWarning) {
                        sendLowStorageNotification()
                        return@launch
                    }

                    startRecording()
                }
            }, 500)
        }
        sendBroadcast(Intent("com.vega.sting.ACTION_STATE_CHANGED"))
        RecordingWidgetProvider.refreshWidget(this)
    }

    private fun onCurrentRecordingFinalized() {
        if (isSwitching && pendingRecordingType != null) {
            recordingType = pendingRecordingType!!
            pendingRecordingType = null
            isSwitching = false
            startRecording()
        }
    }

    private fun handleStopAction() {
        storageMonitorJob?.cancel()
        isSwitching = false
        RecordingStateManager.updateState(RecordingState.STOPPING)
        WidgetStateManager.saveState(this, "IDLE")
        sendBroadcast(Intent("com.vega.sting.ACTION_STATE_CHANGED"))
        RecordingWidgetProvider.refreshWidget(this)
        stopCurrentRecording()
        
        
        if (recordingType == RecordingType.AUDIO) {
            RecordingStateManager.updateState(RecordingState.IDLE)
            WidgetStateManager.saveState(this, "IDLE")
            sendBroadcast(Intent("com.vega.sting.ACTION_STATE_CHANGED"))
            RecordingWidgetProvider.refreshWidget(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
    }

    private fun stopCurrentRecording() {
        val typeToSave = recordingType 
        if (recordingType == RecordingType.AUDIO) {
            audioRecordingManager.stopAudioRecording()
            currentOutputFile?.let { saveRecordingToDb(it, typeToSave) }
        } else {
            videoRecordingManager.stopRecording()
        }
    }

    private fun startRecording() {
        lifecycleScope.launch {
            val isAudio = recordingType == RecordingType.AUDIO
            val fileName = RecordingManager.generateFileName(isAudio)
            
            val settingsManager = SettingsManager(this@RecordingService)
            val location = settingsManager.storageLocation.first()
            
            val storageType = when(location) {
                "SD_CARD" -> StorageType.SD_CARD
                "OTG" -> StorageType.OTG
                else -> StorageType.INTERNAL
            }
            
            val storageDir = StorageManager.getRecordingDirectory(this@RecordingService, storageType)
            
            val outputFile = File(storageDir, fileName)
            currentOutputFile = outputFile

        if (isAudio) {
            audioRecordingManager.startAudioRecording(outputFile)
            RecordingStateManager.updateState(RecordingState.RECORDING_AUDIO, fileName, "AAC")
            WidgetStateManager.saveState(this@RecordingService, "RECORDING_AUDIO")
            sendBroadcast(Intent("com.vega.sting.ACTION_STATE_CHANGED"))
            RecordingWidgetProvider.refreshWidget(this@RecordingService)
            updateRecordingNotification()
        } else {
            val orientationMode = settingsManager.orientationMode.first()

            
            
            
            withTimeoutOrNull(1_500) { profileManager.getOrScanProfile() }

            videoRecordingManager.startVideoRecording(outputFile, orientationMode,
                onStart = { spec ->
                    activeVideoSpec = spec
                    RecordingStateManager.updateState(RecordingState.RECORDING_VIDEO, fileName, spec.codecLabel)
                    WidgetStateManager.saveState(this@RecordingService, "RECORDING_VIDEO")
                    sendBroadcast(Intent("com.vega.sting.ACTION_STATE_CHANGED"))
                    RecordingWidgetProvider.refreshWidget(this@RecordingService)
                    startStorageMonitor()
                    updateRecordingNotification()
                },
                onFinalize = { error ->
                    val finalizedType = RecordingType.VIDEO
                    if (error != null) {
                        RecordingStateManager.setError("Recording Error: $error")
                        activeVideoSpec = null
                        isSwitching = false
                        stopSelf()
                    } else {
                        saveRecordingToDb(outputFile, finalizedType, activeVideoSpec)
                        activeVideoSpec = null
                        
                        if (isSwitching) {
                            onCurrentRecordingFinalized()
                        } else if (RecordingStateManager.state.value == RecordingState.STOPPING) {
                            RecordingStateManager.updateState(RecordingState.IDLE)
                            WidgetStateManager.saveState(this@RecordingService, "IDLE")
                            sendBroadcast(Intent("com.vega.sting.ACTION_STATE_CHANGED"))
                            RecordingWidgetProvider.refreshWidget(this@RecordingService)
                            foregroundStarted = false
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            )
        }
        }
    }

    override fun onDestroy() {
        foregroundStarted = false
        if (!isSwitching) {
            RecordingStateManager.updateState(RecordingState.IDLE)
            WidgetStateManager.saveState(this, "IDLE")
            sendBroadcast(Intent("com.vega.sting.ACTION_STATE_CHANGED"))
            RecordingWidgetProvider.refreshWidget(this)
        }
        super.onDestroy()
    }

    private fun saveRecordingToDb(
        file: File,
        type: RecordingType,
        spec: ActiveRecordingSpec? = null
    ) {
        
        
        val codec = if (type == RecordingType.AUDIO) "AAC" else (spec?.codecLabel ?: "H.264")
        val width = spec?.width ?: 0
        val height = spec?.height ?: 0
        lifecycleScope.launch {
            val recording = Recording(
                name = file.name,
                timestamp = System.currentTimeMillis(),
                path = file.absolutePath,
                type = type,
                codec = codec,
                size = file.length(),
                width = width,
                height = height
            )
            db.recordingDao().insert(recording)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
            setPackage(packageName)
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            9001,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording in Progress")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_stop,
                "STOP",
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun updateRecordingNotification() {
        if (!foregroundStarted) return
        val text = when (recordingType) {
            RecordingType.VIDEO -> "VEGA STING is recording VIDEO..."
            RecordingType.AUDIO -> "VEGA STING is recording AUDIO..."
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, createNotification(text))
    }

    private fun startStorageMonitor() {
        storageMonitorJob?.cancel()
        storageMonitorJob = lifecycleScope.launch {
            while(true) {
                delay(3000)
                if (recordingType == RecordingType.VIDEO) {
                    val file = currentOutputFile ?: continue
                    val free = StorageHealthManager.getFreeSpaceBytes(file.parentFile!!)
                    if (free <= StorageHealthManager.CRITICAL_LEVEL) {
                        switchVideoToAudio()
                        break
                    }
                }
            }
        }
    }

    private fun switchVideoToAudio() {
        sendCriticalStorageNotification()
        isSwitching = true
        pendingRecordingType = RecordingType.AUDIO
        RecordingStateManager.updateState(RecordingState.SWITCHING_TO_AUDIO)
        stopCurrentRecording()
    }

    private fun sendLowStorageNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LOW STORAGE")
            .setContentText("LESS THAN 1GB FREE")
            .setSmallIcon(R.drawable.ic_notification)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1001, notification)
    }

    private fun sendCriticalStorageNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VIDEO STOPPED")
            .setContentText("SWITCHED TO AUDIO DUE TO LOW STORAGE")
            .setSmallIcon(R.drawable.ic_notification)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1002, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Recording Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
