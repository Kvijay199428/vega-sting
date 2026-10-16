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
import com.vega.sting.database.FinalizationReason
import com.vega.sting.database.Recording
import com.vega.sting.database.RecordingType
import com.vega.sting.database.SegmentStatus
import com.vega.sting.recording.ActiveRecordingSpec
import com.vega.sting.recording.AudioRecordingManager
import com.vega.sting.recording.RecordingManager
import com.vega.sting.recording.RecordingSession
import com.vega.sting.recording.RecordingSessionManager
import com.vega.sting.recording.VideoRecordingListener
import com.vega.sting.settings.RecordingSettings
import com.vega.sting.settings.SettingsManager
import com.vega.sting.storage.StorageHealthManager
import com.vega.sting.storage.StorageManager
import com.vega.sting.storage.StoragePolicy
import com.vega.sting.storage.StorageStatus
import com.vega.sting.storage.StorageType
import com.vega.sting.widget.RecordingWidgetProvider
import com.vega.sting.widget.WidgetStateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.vega.sting.camera.DeviceProfileManager
import com.vega.sting.camera.DualCameraCapabilityChecker
import com.vega.sting.camera.DualRecordingPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single authority for the recording lifecycle.
 *
 * Every entry point (Home screen, widget, Quick Settings tile, app shortcut,
 * power-button activity and the automatic video→audio fallback) funnels through
 * the actions here, so the storage policy and the stop/rotate serialization
 * cannot be bypassed by a shortcut.
 *
 * All mutation happens on the main thread (service lifecycle + coroutine
 * dispatchers + `mainExecutor` callbacks from the recording managers), and
 * `stopInProgress` guards the decision points so two stop/rotate operations can
 * never run concurrently.
 */
class RecordingService : LifecycleService() {
    companion object {
        const val ACTION_START_VIDEO = "ACTION_START_VIDEO"
        const val ACTION_START_AUDIO = "ACTION_START_AUDIO"
        const val ACTION_STOP = "ACTION_STOP"

        private const val CHANNEL_ID = "RecordingServiceChannel"
        private const val ALERT_CHANNEL_ID = "StorageAlertChannel"
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 1001
    }

    private lateinit var videoRecordingManager: RecordingManager
    private lateinit var audioRecordingManager: AudioRecordingManager
    private lateinit var db: AppDatabase
    private lateinit var profileManager: DeviceProfileManager
    private lateinit var settingsManager: SettingsManager

    /** Non-null only while a dual-camera (composited) video session is active. */
    private var dualRecordingManager: DualRecordingPipeline? = null
    private var usingDualPipeline = false

    private var recordingType = RecordingType.VIDEO
    private var session: RecordingSession? = null
    private var sessionSettings: RecordingSettings = RecordingSettings.DEFAULTS
    private var recordingDirectory: File? = null
    private var currentSegmentFile: File? = null
    private var currentPartIndex = 1
    private var activeVideoSpec: ActiveRecordingSpec? = null

    private var isSwitching = false
    private var pendingRecordingType: RecordingType? = null
    private var storageMonitorJob: Job? = null
    private var ignoreWarning = false
    private var foregroundStarted = false

    // Guards against concurrent stop/rotate/switch operations.
    private var stopInProgress = false
    private var manualStopRequested = false
    private var lastStorageStatus: StorageStatus? = null

    override fun onCreate() {
        super.onCreate()
        videoRecordingManager = RecordingManager(this)
        audioRecordingManager = AudioRecordingManager(this)
        db = AppDatabase.getDatabase(this)
        profileManager = DeviceProfileManager(this)
        settingsManager = SettingsManager(this)
        createNotificationChannels()

        lifecycleScope.launch {
            try {
                profileManager.getOrScanProfile()
                val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                db.cameraSessionTelemetryDao().deleteOlderThan(thirtyDaysAgo)
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent == null) return START_STICKY

        ignoreWarning = intent.getBooleanExtra("IGNORE_STORAGE_WARNING", false)

        when (intent.action) {
            ACTION_START_VIDEO -> handleStartAction(RecordingType.VIDEO)
            ACTION_START_AUDIO -> handleStartAction(RecordingType.AUDIO)
            ACTION_STOP -> handleStopAction()
            else -> {
                val typeStr = intent.getStringExtra("EXTRA_TYPE") ?: "VIDEO"
                val type = if (typeStr == "AUDIO") RecordingType.AUDIO else RecordingType.VIDEO
                handleStartAction(type)
            }
        }

        return START_STICKY
    }

    // ---- Start / stop / switch ---------------------------------------------

    private fun handleStartAction(newType: RecordingType) {
        // A stop that is still unwinding must finish first, otherwise a second
        // recorder could be built on top of the one that is being torn down.
        if (stopInProgress || RecordingStateManager.state.value == RecordingState.STOPPING) return
        if (RecordingStateManager.isRecording()) {
            if (newType != recordingType) {
                switchRecordingType(newType)
            }
            return
        }
        // Foreground must be established quickly; the safety checks then decide
        // whether the recording actually begins.
        ensureForeground()
        lifecycleScope.launch { startFresh(newType) }
    }

    private suspend fun startFresh(newType: RecordingType) {
        stopInProgress = false
        manualStopRequested = false
        lastStorageStatus = null

        val settings = settingsManager.recordingSettings.first()
        sessionSettings = settings
        val directory = StorageManager.getRecordingDirectory(this, readStorageType())
        recordingDirectory = directory

        val snapshot = StorageHealthManager.snapshot(directory)
        val status = StoragePolicy.statusFor(
            snapshot.freeBytes,
            settings.lowStorageWarningBytes,
            settings.minimumFreeSpaceBytes,
            snapshot.available
        )

        val allowed = when (newType) {
            RecordingType.VIDEO ->
                StoragePolicy.canStartVideo(snapshot.freeBytes, settings.minimumFreeSpaceBytes) &&
                    (!StoragePolicy.videoRequiresConfirmation(
                        snapshot.freeBytes, settings.lowStorageWarningBytes, settings.minimumFreeSpaceBytes
                    ) || ignoreWarning)
            RecordingType.AUDIO ->
                StoragePolicy.canStartAudio(snapshot.freeBytes, settings.minimumFreeSpaceBytes)
        }

        if (!allowed) {
            rejectStart(newType, status, snapshot, settings)
            return
        }

        recordingType = newType
        isSwitching = false
        pendingRecordingType = null
        session = RecordingSessionManager.createSession(directory, newType == RecordingType.AUDIO)
        currentPartIndex = 1
        currentSegmentFile = null
        lastStorageStatus = status
        usingDualPipeline = false
        dualRecordingManager = null

        RecordingStateManager.updateState(
            if (newType == RecordingType.AUDIO) RecordingState.STARTING_AUDIO else RecordingState.STARTING_VIDEO
        )
        WidgetStateManager.saveState(
            this,
            if (newType == RecordingType.AUDIO) "RECORDING_AUDIO" else "RECORDING_VIDEO"
        )
        broadcastState()
        startRecording()
    }

    private fun startRecording() {
        val activeSession = session ?: return
        val directory = recordingDirectory ?: return
        val settings = sessionSettings

        if (activeSession.isAudio) {
            val limit = if (settings.splitRecordingEnabled) settings.audioSegmentLimitBytes else 0L
            audioRecordingManager.startAudioRecording(
                directory,
                activeSession,
                limit,
                settings.splitRecordingEnabled,
                audioListener
            )
            startStorageMonitor()
        } else {
            lifecycleScope.launch {
                withTimeoutOrNull(1_500) { profileManager.getOrScanProfile() }
                val orientationMode = settingsManager.orientationMode.first()
                val limit = if (settings.splitRecordingEnabled) settings.videoSegmentLimitBytes else 0L

                // Dual camera is used only when the user enabled it *and* the
                // device can genuinely capture both cameras concurrently. When
                // it cannot, the existing single-camera pipeline records instead.
                val dualConfig = settingsManager.dualCameraConfig.first()
                val dualReady = dualConfig.enabled && withContext(Dispatchers.IO) {
                    DualCameraCapabilityChecker(this@RecordingService).check().canOfferDualCamera
                }

                if (dualReady) {
                    usingDualPipeline = true
                    val pipeline = DualRecordingPipeline(this@RecordingService)
                    dualRecordingManager = pipeline
                    pipeline.startVideoRecording(
                        directory,
                        activeSession,
                        dualConfig,
                        isPortraitForMode(orientationMode),
                        limit,
                        settings.splitRecordingEnabled,
                        videoListener
                    )
                } else {
                    usingDualPipeline = false
                    videoRecordingManager.startVideoRecording(
                        directory,
                        activeSession,
                        orientationMode,
                        limit,
                        settings.splitRecordingEnabled,
                        videoListener
                    )
                }
                startStorageMonitor()
            }
        }
    }

    /** Resolves the composite orientation for the current device posture. */
    private fun isPortraitForMode(orientationMode: String): Boolean = when (orientationMode) {
        "PORTRAIT" -> true
        "LANDSCAPE" -> false
        else -> resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    private fun handleStopAction() {
        if (manualStopRequested) return
        manualStopRequested = true
        storageMonitorJob?.cancel()
        storageMonitorJob = null
        isSwitching = false
        pendingRecordingType = null
        RecordingStateManager.updateState(RecordingState.STOPPING)
        WidgetStateManager.saveState(this, "IDLE")
        broadcastState()
        cancelAlert()
        lifecycleScope.launch { stopCurrentRecording(FinalizationReason.MANUAL_STOP) }
    }

    private fun switchRecordingType(newType: RecordingType) {
        if (stopInProgress || manualStopRequested) return
        stopInProgress = true
        storageMonitorJob?.cancel()
        storageMonitorJob = null
        isSwitching = true
        pendingRecordingType = newType
        RecordingStateManager.updateState(
            if (newType == RecordingType.AUDIO) RecordingState.SWITCHING_TO_AUDIO else RecordingState.SWITCHING_TO_VIDEO
        )
        WidgetStateManager.saveState(
            this,
            if (newType == RecordingType.AUDIO) "RECORDING_AUDIO" else "RECORDING_VIDEO"
        )
        broadcastState()
        lifecycleScope.launch { stopCurrentRecording(FinalizationReason.SWITCH) }
    }

    private suspend fun stopCurrentRecording(reason: FinalizationReason) {
        when (recordingType) {
            RecordingType.AUDIO -> {
                audioRecordingManager.stopAudioRecording(reason)
                onRecordingSessionEnded(reason, null)
            }
            RecordingType.VIDEO ->
                if (usingDualPipeline) {
                    dualRecordingManager?.stopRecording(reason)
                } else {
                    videoRecordingManager.stopRecording(reason)
                }
        }
    }

    /** Terminal handling for one full session (all its parts). */
    private suspend fun onRecordingSessionEnded(reason: FinalizationReason, error: String?) {
        storageMonitorJob?.cancel()
        storageMonitorJob = null

        session?.sessionId?.let { sessionId ->
            try {
                db.recordingDao().completeSession(sessionId)
            } catch (_: Exception) {}
        }
        session = null
        currentSegmentFile = null
        stopInProgress = false
        dualRecordingManager = null
        usingDualPipeline = false

        if (error != null) {
            activeVideoSpec = null
            RecordingStateManager.setError("Recording Error: $error")
            WidgetStateManager.saveState(this, "IDLE")
            broadcastState()
            releaseForeground()
            stopSelf()
            return
        }

        if (isSwitching && pendingRecordingType != null) {
            val next = pendingRecordingType!!
            pendingRecordingType = null
            isSwitching = false
            startFresh(next)
            return
        }

        activeVideoSpec = null
        RecordingStateManager.updateState(RecordingState.IDLE)
        WidgetStateManager.saveState(this, "IDLE")
        broadcastState()
        if (reason == FinalizationReason.STORAGE_LIMIT) {
            // A dedicated alert is posted by the stop request; nothing further here.
        }
        releaseForeground()
        stopSelf()
    }

    // ---- Storage monitoring ------------------------------------------------

    private fun startStorageMonitor() {
        storageMonitorJob?.cancel()
        storageMonitorJob = lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                if (!RecordingStateManager.isRecording()) break
                evaluateStorageDuringRecording()
            }
        }
    }

    private suspend fun evaluateStorageDuringRecording() {
        val directory = recordingDirectory ?: return
        val settings = sessionSettings
        val snapshot = StorageHealthManager.snapshot(directory)
        val status = StoragePolicy.statusFor(
            snapshot.freeBytes,
            settings.lowStorageWarningBytes,
            settings.minimumFreeSpaceBytes,
            snapshot.available
        )
        handleStorageTransition(status, snapshot)

        when {
            !snapshot.available ->
                requestStorageStop("Recording storage is unavailable")

            StoragePolicy.shouldStopRecording(snapshot.freeBytes, settings.minimumFreeSpaceBytes) ->
                requestStorageStop("Storage reached the safety reserve")

            recordingType == RecordingType.VIDEO &&
                !StoragePolicy.canContinueVideo(snapshot.freeBytes, settings.minimumFreeSpaceBytes) -> {
                if (settings.audioFallbackEnabled &&
                    StoragePolicy.canStartAudio(snapshot.freeBytes, settings.minimumFreeSpaceBytes)
                ) {
                    switchVideoToAudio()
                } else {
                    requestStorageStop("Video storage is critical and audio fallback is not safe")
                }
            }
        }
    }

    private fun requestStorageStop(reasonText: String) {
        if (stopInProgress || manualStopRequested) return
        stopInProgress = true
        storageMonitorJob?.cancel()
        storageMonitorJob = null
        postAlert(
            "Recording stopped to protect your files",
            "$reasonText. Clear space before recording again."
        )
        RecordingStateManager.updateState(RecordingState.STOPPING)
        WidgetStateManager.saveState(this, "IDLE")
        broadcastState()
        lifecycleScope.launch { stopCurrentRecording(FinalizationReason.STORAGE_LIMIT) }
    }

    private fun switchVideoToAudio() {
        if (stopInProgress || manualStopRequested) return
        stopInProgress = true
        storageMonitorJob?.cancel()
        storageMonitorJob = null
        postAlert(
            "Video stopped — audio recording continues",
            "Storage is low. Free space is being monitored."
        )
        isSwitching = true
        pendingRecordingType = RecordingType.AUDIO
        RecordingStateManager.updateState(RecordingState.SWITCHING_TO_AUDIO)
        WidgetStateManager.saveState(this, "RECORDING_AUDIO")
        broadcastState()
        lifecycleScope.launch { stopCurrentRecording(FinalizationReason.SWITCH) }
    }

    /** Re-checks free space the moment a rotated part begins. */
    private fun onNewPartStarted(partIndex: Int) {
        val activeSession = session ?: return
        if (partIndex <= activeSession.startPartIndex) return
        lifecycleScope.launch {
            val directory = recordingDirectory ?: return@launch
            val settings = sessionSettings
            val snapshot = StorageHealthManager.snapshot(directory)
            if (!snapshot.available ||
                StoragePolicy.shouldStopRecording(snapshot.freeBytes, settings.minimumFreeSpaceBytes)
            ) {
                requestStorageStop("Storage reached the safety reserve")
            }
        }
    }

    // ---- Recorder listeners ------------------------------------------------

    private val audioListener = object : AudioRecordingManager.Listener {
        override fun onSegmentStarted(file: File, partIndex: Int) {
            onSegmentStarted(RecordingType.AUDIO, file, partIndex, "AAC")
        }

        override fun onSegmentFinalized(
            file: File,
            partIndex: Int,
            reason: FinalizationReason,
            gapPossible: Boolean
        ) {
            finalizeSegment(RecordingType.AUDIO, file, partIndex, reason, null)
        }

        override fun onRecorderError(message: String) {
            this@RecordingService.onRecorderError(message)
        }
    }

    private val videoListener = object : VideoRecordingListener {
        override fun onSegmentStarted(file: File, partIndex: Int, spec: ActiveRecordingSpec) {
            activeVideoSpec = spec
            onSegmentStarted(RecordingType.VIDEO, file, partIndex, spec.codecLabel)
        }

        override fun onSegmentFinalized(
            file: File,
            partIndex: Int,
            reason: FinalizationReason,
            gapPossible: Boolean
        ) {
            finalizeSegment(RecordingType.VIDEO, file, partIndex, reason, activeVideoSpec)
        }

        override fun onRecorderError(message: String) {
            this@RecordingService.onRecorderError(message)
        }

        override fun onRecordingFinalized(reason: FinalizationReason, error: String?) {
            lifecycleScope.launch { onRecordingSessionEnded(reason, error) }
        }
    }

    private fun onSegmentStarted(
        type: RecordingType,
        file: File,
        partIndex: Int,
        codec: String
    ) {
        currentSegmentFile = file
        currentPartIndex = partIndex
        RecordingStateManager.updateState(
            if (type == RecordingType.AUDIO) RecordingState.RECORDING_AUDIO else RecordingState.RECORDING_VIDEO,
            file.name,
            codec
        )
        WidgetStateManager.saveState(
            this,
            if (type == RecordingType.AUDIO) "RECORDING_AUDIO" else "RECORDING_VIDEO"
        )
        updateRecordingNotification()
        broadcastState()
        RecordingWidgetProvider.refreshWidget(this)
        persistSegmentStart(type, file, partIndex, codec)
        onNewPartStarted(partIndex)
    }

    /**
     * Records the existence of an in-progress part so a crash mid-segment can be
     * detected and finalised on the next launch. Hidden from the library while
     * `segmentStatus = RECORDING`.
     */
    private fun persistSegmentStart(
        type: RecordingType,
        file: File,
        partIndex: Int,
        codec: String
    ) {
        val activeSession = session ?: return
        val width = if (type == RecordingType.AUDIO) 0 else (activeVideoSpec?.width ?: 0)
        val height = if (type == RecordingType.AUDIO) 0 else (activeVideoSpec?.height ?: 0)
        lifecycleScope.launch {
            try {
                db.recordingDao().insertIfAbsent(
                    Recording(
                        name = file.name,
                        timestamp = System.currentTimeMillis(),
                        path = file.absolutePath,
                        type = type,
                        codec = codec,
                        size = 0L,
                        sessionId = activeSession.sessionId,
                        partIndex = partIndex,
                        segmentStatus = SegmentStatus.RECORDING.name,
                        startedAt = System.currentTimeMillis(),
                        width = width,
                        height = height
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private fun onRecorderError(message: String) {
        activeVideoSpec = null
        stopInProgress = false
        RecordingStateManager.setError("Recording Error: $message")
        WidgetStateManager.saveState(this, "IDLE")
        broadcastState()
        releaseForeground()
        stopSelf()
    }

    private fun finalizeSegment(
        type: RecordingType,
        file: File,
        partIndex: Int,
        reason: FinalizationReason,
        spec: ActiveRecordingSpec?
    ) {
        val activeSession = session ?: return
        val codec = if (type == RecordingType.AUDIO) "AAC" else (spec?.codecLabel ?: "H.264")
        val width = if (type == RecordingType.AUDIO) 0 else (spec?.width ?: 0)
        val height = if (type == RecordingType.AUDIO) 0 else (spec?.height ?: 0)
        lifecycleScope.launch {
            try {
                val size = file.length()
                val dao = db.recordingDao()
                if (size <= 0L) {
                    // A part that never received any data would clutter the
                    // library; drop the placeholder row the start wrote.
                    dao.deleteSegment(activeSession.sessionId, partIndex)
                    return@launch
                }
                val ok = reason != FinalizationReason.ERROR
                val status = if (ok) SegmentStatus.FINALIZED else SegmentStatus.FAILED
                // Guarantee a row exists for this part (idempotent), then finalize it.
                dao.insertIfAbsent(
                    Recording(
                        name = file.name,
                        timestamp = System.currentTimeMillis(),
                        path = file.absolutePath,
                        type = type,
                        codec = codec,
                        size = 0L,
                        sessionId = activeSession.sessionId,
                        partIndex = partIndex,
                        segmentStatus = SegmentStatus.RECORDING.name,
                        startedAt = System.currentTimeMillis(),
                        width = width,
                        height = height
                    )
                )
                dao.finalizeSegment(
                    sessionId = activeSession.sessionId,
                    partIndex = partIndex,
                    name = file.name,
                    path = file.absolutePath,
                    size = size,
                    codec = codec,
                    width = width,
                    height = height,
                    endedAt = System.currentTimeMillis(),
                    reason = reason.name,
                    status = status.name
                )
            } catch (_: Exception) {}
        }
    }

    // ---- Start rejection ---------------------------------------------------

    private fun rejectStart(
        type: RecordingType,
        status: StorageStatus,
        snapshot: StorageHealthManager.StorageSnapshot,
        settings: RecordingSettings
    ) {
        val free = StorageHealthManager.formatFileSize(snapshot.freeBytes)
        val reserve = StorageHealthManager.formatFileSize(settings.minimumFreeSpaceBytes)
        val title: String
        val text: String
        when (status) {
            StorageStatus.STORAGE_UNAVAILABLE -> {
                title = "Storage unavailable"
                text = "The recording destination is missing or not writable."
            }
            StorageStatus.STORAGE_ERROR -> {
                title = "Storage could not be checked"
                text = "Free space could not be verified, so recording was not started."
            }
            StorageStatus.RESERVE_REACHED -> {
                title = "Not enough free space"
                text = "$free free. At least $reserve must be kept in reserve."
            }
            else -> {
                title = "Not enough free space for ${type.name.lowercase()}"
                text = "$free free is not enough to start safely."
            }
        }
        postAlert(title, text)
        RecordingStateManager.setError("$title: $text")
        WidgetStateManager.saveState(this, "IDLE")
        broadcastState()
        releaseForeground()
        stopSelf()
    }

    // ---- Notifications -----------------------------------------------------

    private fun ensureForeground() {
        if (!foregroundStarted) {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
            foregroundStarted = true
        }
    }

    private fun updateRecordingNotification() {
        if (!foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
    }

    private fun buildForegroundNotification(): Notification {
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

        val typeLabel = when {
            recordingType == RecordingType.AUDIO -> "AUDIO"
            usingDualPipeline -> "VIDEO (DUAL CAM)"
            else -> "VIDEO"
        }
        val free = recordingDirectory?.let {
            StorageHealthManager.formatFileSize(StorageHealthManager.getFreeSpaceBytes(it))
        } ?: "--"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VEGA STING is recording $typeLabel")
            .setContentText("part-$currentPartIndex · Free space: $free")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "STOP", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun handleStorageTransition(
        status: StorageStatus,
        snapshot: StorageHealthManager.StorageSnapshot
    ) {
        if (status == lastStorageStatus) return
        lastStorageStatus = status
        val free = StorageHealthManager.formatFileSize(snapshot.freeBytes)
        when (status) {
            StorageStatus.LOW -> postAlert(
                "Storage running low",
                "Free space: $free. Video recording may be restricted."
            )
            StorageStatus.CRITICAL -> postAlert(
                "Storage critical",
                "Free space: $free. Switching to audio-only if enabled."
            )
            StorageStatus.STORAGE_UNAVAILABLE -> postAlert(
                "Storage unavailable",
                "The recording destination can no longer be read."
            )
            StorageStatus.STORAGE_ERROR -> postAlert(
                "Storage check failed",
                "Free space could not be verified."
            )
            StorageStatus.NORMAL -> cancelAlert()
            StorageStatus.RESERVE_REACHED -> { /* the stop flow posts its own alert */ }
        }
    }

    private fun postAlert(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun cancelAlert() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(ALERT_NOTIFICATION_ID)
    }

    private fun releaseForeground() {
        foregroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Recording Service Channel",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            manager?.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Storage Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    // ---- Helpers -----------------------------------------------------------

    private suspend fun readStorageType(): StorageType =
        when (settingsManager.storageLocation.first()) {
            "SD_CARD" -> StorageType.SD_CARD
            "OTG" -> StorageType.OTG
            else -> StorageType.INTERNAL
        }

    private fun broadcastState() {
        sendBroadcast(Intent("com.vega.sting.ACTION_STATE_CHANGED"))
        RecordingWidgetProvider.refreshWidget(this)
    }

    override fun onDestroy() {
        storageMonitorJob?.cancel()
        storageMonitorJob = null
        if (!isSwitching && RecordingStateManager.state.value != RecordingState.ERROR) {
            RecordingStateManager.updateState(RecordingState.IDLE)
            WidgetStateManager.saveState(this, "IDLE")
            broadcastState()
        }
        super.onDestroy()
    }
}
