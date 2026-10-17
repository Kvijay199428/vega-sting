package com.vega.sting.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import com.vega.sting.core.RecordingStateManager
import com.vega.sting.R
import com.vega.sting.legal.LegalDocument
import com.vega.sting.overlay.OverlayPosition
import com.vega.sting.settings.SettingsManager
import com.vega.sting.settings.RecordingSettings
import com.vega.sting.updater.UpdateChannel
import com.vega.sting.ui.components.DetailHeaderBar
import com.vega.sting.ui.legal.LegalDocumentDialog
import com.vega.sting.storage.StorageHealthManager
import com.vega.sting.storage.StorageManager
import com.vega.sting.storage.StorageType
import com.vega.sting.ui.theme.*
import com.vega.sting.camera.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    viewModel: com.vega.sting.ui.viewmodel.RecordingViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBack: () -> Unit,
    
    onCheckForUpdates: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }
    var openLegalDocument by remember { mutableStateOf<LegalDocument?>(null) }
    
val videoCodec by settingsManager.videoCodec.collectAsState(initial = "H.265")
    val videoSize by settingsManager.resolution.collectAsState(initial = "AUTO")
    val videoRatio by settingsManager.videoRatio.collectAsState(initial = "AUTO")
    val powerMode by settingsManager.powerButtonMode.collectAsState(initial = "VIDEO")
    val storageLocation by settingsManager.storageLocation.collectAsState(initial = "INTERNAL")
    val orientationMode by settingsManager.orientationMode.collectAsState(initial = "FOLLOW_SENSOR")
    val timestampEnabled by settingsManager.timestampEnabled.collectAsState(initial = true)
    val timestampPosition by settingsManager.timestampPosition.collectAsState(initial = "TOP_CENTER")
    val timestampFormat by settingsManager.timestampFormat.collectAsState(initial = "yyyy-MM-dd HH:mm:ss")
    val signature by settingsManager.signature.collectAsState(initial = "VEGA STING")
    val watermarkEnabled by settingsManager.watermarkEnabled.collectAsState(initial = true)
    val signaturePosition by settingsManager.signaturePosition.collectAsState(initial = "BOTTOM_LEFT")
    val overlayTextSize by settingsManager.overlayTextSize.collectAsState(initial = 18)
    val splitEnabled by settingsManager.splitRecordingEnabled.collectAsState(initial = true)
    val videoLimit by settingsManager.videoSegmentLimitBytes
        .collectAsState(initial = RecordingSettings.DEFAULTS.videoSegmentLimitBytes)
    val audioLimit by settingsManager.audioSegmentLimitBytes
        .collectAsState(initial = RecordingSettings.DEFAULTS.audioSegmentLimitBytes)
    val reserveBytes by settingsManager.minimumFreeSpaceBytes
        .collectAsState(initial = RecordingSettings.DEFAULTS.minimumFreeSpaceBytes)
    val audioFallback by settingsManager.audioFallbackEnabled.collectAsState(initial = true)
    val supportReminders by settingsManager.supportRemindersEnabled.collectAsState(initial = true)
    
    // Update channel: the persisted preference, falling back to the channel that
    // was baked into the installed APK.
    val selectedChannel by settingsManager.selectedUpdateChannel
        .collectAsState(initial = UpdateChannel.installed())
    var pendingChannel by remember { mutableStateOf<UpdateChannel?>(null) }
    
    var availableStorages by remember { mutableStateOf(StorageManager.getAvailableStorages(context)) }
    var activeDialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var showSignatureEditor by remember { mutableStateOf(false) }
    var showDualCameraPreview by remember { mutableStateOf(false) }
    var signatureDraft by remember { mutableStateOf(signature) }

    // Dual-camera configuration (single source of truth) and capability state.
    val dualConfig by settingsManager.dualCameraConfig
        .collectAsState(initial = DualCameraConfig.DEFAULTS)
    var dualCapability by remember { mutableStateOf<DualCameraCapabilityResult?>(null) }
    var checkingDualCapability by remember { mutableStateOf(false) }
    var capabilityCheckTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(dualConfig.enabled, capabilityCheckTrigger) {
        checkingDualCapability = true
        val result = withContext(Dispatchers.IO) {
            DualCameraCapabilityChecker(context).check()
        }
        dualCapability = result
        checkingDualCapability = false

        // A device that cannot run dual capture must not be left with the mode
        // switched on: clear the persisted flag so every control — including the
        // mode toggle — starts and stays disabled. A transient enumeration error
        // only disables the rows and leaves the stored preference alone.
        val terminalUnsupported =
            result.status == DualCameraCapabilityStatus.NO_CAMERAS ||
                result.status == DualCameraCapabilityStatus.MISSING_FACING_CAMERA ||
                result.status == DualCameraCapabilityStatus.CONCURRENT_UNSUPPORTED
        if (terminalUnsupported && dualConfig.enabled) {
            settingsManager.updateDualCameraConfig(dualConfig.copy(enabled = false))
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                availableStorages = StorageManager.getAvailableStorages(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val trashRecordings by viewModel.trashRecordings.collectAsState(initial = emptyList())
    val trashSizeBytes = trashRecordings.sumOf { it.size }
    val trashSizeStr = StorageHealthManager.formatFileSize(trashSizeBytes)

    
    
    
    val recordingState by RecordingStateManager.state.collectAsState()
    val isRecording = RecordingStateManager.isBusy(recordingState)

    // Master-toggle gating: an ENABLE/DISABLE switch disables its dependent
    // rows exactly the way DUAL CAMERA MODE disables the dual-camera details.
    // WATERMARK is the true master of the overlay group (see
    // OverlaySettingsComposer), so it gates every other overlay row.
    val overlayEnabled = watermarkEnabled
    val timestampDetailEnabled = overlayEnabled && timestampEnabled
    val signaturePresent = signature.isNotBlank()
    val signatureDetailEnabled = overlayEnabled && signaturePresent
    val textSizeEnabled = overlayEnabled && (timestampEnabled || signaturePresent)

    if (showSignatureEditor) {
        AlertDialog(
            onDismissRequest = { showSignatureEditor = false },
            title = { Text("SIGNATURE", color = AccentYellow) },
            text = {
                Column {
                    OutlinedTextField(
                        value = signatureDraft,
                        onValueChange = { signatureDraft = it },
                        singleLine = true,
                        label = { Text("DRAWN ON VIDEO") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Leave empty to hide the signature.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { settingsManager.updateSignature(signatureDraft.trim()) }
                    showSignatureEditor = false
                }) {
                    Text("SAVE", color = AccentOrange)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignatureEditor = false }) {
                    Text("CANCEL", color = TextSecondary)
                }
            },
            containerColor = Panel,
            shape = MaterialTheme.shapes.extraSmall
        )
    }

    if (showDualCameraPreview) {
        val configuration = LocalConfiguration.current
        val portraitOutput = when (orientationMode) {
            "PORTRAIT" -> true
            "LANDSCAPE" -> false
            else -> configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        }
        DualCameraPreviewDialog(
            config = dualConfig,
            orientationMode = orientationMode,
            portraitOutput = portraitOutput,
            onDismiss = { showDualCameraPreview = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        DetailHeaderBar(title = "SETTINGS", onBack = onBack)

        SettingsSection(title = "RECORDING") {
            SettingsItem(
                label = "VIDEO CODEC", 
                value = videoCodec,
                onClick = { activeDialog = SettingsDialog.VIDEO_CODEC }
            )
            SettingsItem(
                label = "VIDEO SIZE",
                value = videoSize,
                onClick = { activeDialog = SettingsDialog.VIDEO_SIZE }
            )
            SettingsItem(
                label = "VIDEO RATIO",
                value = videoRatio,
                onClick = { activeDialog = SettingsDialog.VIDEO_RATIO }
            )
            
            
            
            SettingsItem(
                label = "ORIENTATION", 
                value = orientationLabel(orientationMode),
                onClick = { activeDialog = SettingsDialog.ORIENTATION }
            )
        }

        SettingsSection(title = "FILE SIZE & STORAGE SAFETY") {
            SettingsItem(
                label = "FILE SPLITTING",
                value = if (splitEnabled) "ENABLED" else "DISABLED",
                onClick = {
                    scope.launch { settingsManager.updateSplitRecordingEnabled(!splitEnabled) }
                }
            )
            SettingsItem(
                label = "VIDEO FILE LIMIT",
                value = StorageHealthManager.formatFileSize(videoLimit),
                onClick = if (splitEnabled) {
                    { activeDialog = SettingsDialog.VIDEO_LIMIT }
                } else null
            )
            SettingsItem(
                label = "AUDIO FILE LIMIT",
                value = StorageHealthManager.formatFileSize(audioLimit),
                onClick = if (splitEnabled) {
                    { activeDialog = SettingsDialog.AUDIO_LIMIT }
                } else null
            )
            SettingsItem(
                label = "FREE-SPACE RESERVE",
                value = StorageHealthManager.formatFileSize(reserveBytes),
                onClick = { activeDialog = SettingsDialog.RESERVE }
            )
            SettingsItem(
                label = "AUDIO FALLBACK",
                value = if (audioFallback) "ENABLED" else "DISABLED",
                onClick = {
                    scope.launch { settingsManager.updateAudioFallbackEnabled(!audioFallback) }
                }
            )
            Text(
                "Preview — video ${StorageHealthManager.formatFileSize(videoLimit)} · " +
                    "audio ${StorageHealthManager.formatFileSize(audioLimit)} · " +
                    "reserve ${StorageHealthManager.formatFileSize(reserveBytes)} · " +
                    "fallback ${if (audioFallback) "on" else "off"}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        SettingsSection(title = "OVERLAY") {
            SettingsItem(
                label = "WATERMARK",
                value = if (watermarkEnabled) "ENABLED" else "DISABLED",
                onClick = {
                    scope.launch { settingsManager.updateWatermark(!watermarkEnabled) }
                }
            )
            SettingsItem(
                label = "TIMESTAMP",
                value = if (timestampEnabled) "ENABLED" else "DISABLED",
                onClick = if (overlayEnabled) {
                    { scope.launch { settingsManager.updateTimestampEnabled(!timestampEnabled) } }
                } else null
            )
            SettingsItem(
                label = "TIMESTAMP POSITION",
                value = OverlayPosition.from(timestampPosition).displayName(),
                onClick = if (timestampDetailEnabled) {
                    { activeDialog = SettingsDialog.TIMESTAMP_POSITION }
                } else null
            )
            SettingsItem(
                label = "TIMESTAMP FORMAT",
                value = timestampFormat,
                onClick = if (timestampDetailEnabled) {
                    { activeDialog = SettingsDialog.TIMESTAMP_FORMAT }
                } else null
            )
            SettingsItem(
                label = "SIGNATURE",
                value = if (signature.isBlank()) "OFF" else signature,
                onClick = if (overlayEnabled) { { showSignatureEditor = true } } else null
            )
            SettingsItem(
                label = "SIGNATURE POSITION",
                value = OverlayPosition.from(signaturePosition).displayName(),
                onClick = if (signatureDetailEnabled) {
                    { activeDialog = SettingsDialog.SIGNATURE_POSITION }
                } else null
            )
            SettingsItem(
                label = "TEXT SIZE",
                value = overlayTextSize.toString(),
                onClick = if (textSizeEnabled) { { activeDialog = SettingsDialog.TEXT_SIZE } } else null
            )
        }

        SettingsSection(title = "STORAGE") {
            availableStorages.forEach {
                val free = StorageHealthManager.formatBytes(
                    StorageHealthManager.getFreeSpaceBytes(it.second)
                )
                SettingsItem(
                    label = it.first.name,
                    value = free
                )
            }
            SettingsItem(
                label = "ACTIVE",
                value = storageLocation,
                onClick = { activeDialog = SettingsDialog.STORAGE }
            )
            SettingsItem(label = "DIRECTORY", value = "VEGA STING")
        }

        SettingsSection(title = "TRASH") {
            SettingsItem(label = "TRASH SIZE", value = trashSizeStr)
            SettingsItem(label = "AUTO CLEANUP", value = "30 DAYS")
        }

        SettingsSection(title = "REMINDERS") {
            SettingsItem(
                label = "OCCASIONAL SUPPORT REMINDERS",
                value = if (supportReminders) "ENABLED" else "DISABLED",
                onClick = {
                    scope.launch { settingsManager.updateSupportRemindersEnabled(!supportReminders) }
                    if (supportReminders) {
                        // disabling
                    } else {
                        // enabling - schedule initial if not set
                        scope.launch {
                            val now = System.currentTimeMillis()
                            val next = java.util.concurrent.TimeUnit.DAYS.toMillis(7)
                            settingsManager.setNextSupportReminderAt(now + next)
                            com.vega.sting.reminders.SupportReminderScheduler.scheduleAt(context, next)
                        }
                    }
                }
            )
        }

        DualCameraSettingsSection(
            config = dualConfig,
            capability = dualCapability,
            checkingCapability = checkingDualCapability,
            recordingActive = isRecording,
            onConfigChange = { scope.launch { settingsManager.updateDualCameraConfig(it) } },
            onReset = { scope.launch { settingsManager.resetDualCameraConfig() } },
            onPreview = { showDualCameraPreview = true },
            onRefreshCapability = { capabilityCheckTrigger++ }
        )

        SettingsSection(title = "HARDWARE") {
            SettingsItem(
                label = "POWER ACTION", 
                value = if (powerMode == "VIDEO") "TOGGLE AV" else "TOGGLE AUDIO",
                onClick = { activeDialog = SettingsDialog.POWER_ACTION }
            )
            SettingsItem(label = "QUICK TILE", value = "ENABLED")
        }

        SettingsSection(title = "LEGAL") {
            LegalDocument.entries.forEach { document ->
                SettingsItem(
                    label = context.getString(document.titleRes),
                    value = "READ",
                    onClick = { openLegalDocument = document }
                )
            }
        }

        SettingsSection(title = stringResource(R.string.settings_update_channel)) {
            SettingsItem(
                label = stringResource(R.string.update_channel_installed),
                value = "${UpdateChannel.installed().label} · ${com.vega.sting.BuildConfig.VERSION_NAME}"
            )
            SettingsItem(
                label = stringResource(R.string.update_channel_updates),
                value = selectedChannel.label
            )
            SettingsItem(
                label = stringResource(R.string.update_channel_stable_title),
                value = if (selectedChannel == UpdateChannel.STABLE) "SELECTED" else "TAP TO SWITCH",
                onClick = if (selectedChannel == UpdateChannel.STABLE) null
                else { { pendingChannel = UpdateChannel.STABLE } }
            )
            Text(
                text = stringResource(R.string.update_channel_stable_desc),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
            SettingsItem(
                label = stringResource(R.string.update_channel_debug_title),
                value = if (selectedChannel == UpdateChannel.DEBUG) "SELECTED" else "TAP TO SWITCH",
                onClick = if (selectedChannel == UpdateChannel.DEBUG) null
                else { { pendingChannel = UpdateChannel.DEBUG } }
            )
            Text(
                text = stringResource(R.string.update_channel_debug_desc),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }

        SettingsSection(title = "ABOUT") {
            SettingsItem(label = "DEVELOPER", value = com.vega.sting.BuildConfig.DEV_NAME, valueIcon = R.drawable.ic_vijaykrsha_logo)
            SettingsItem(
                label = "WEBSITE",
                value = "LINK",
                onClick = { openUrl(context, com.vega.sting.BuildConfig.DEV_WEBSITE) }
            )
            SettingsItem(
                label = "SOURCE",
                value = "GITHUB",
                onClick = { openUrl(context, com.vega.sting.BuildConfig.GITHUB_REPO_URL) }
            )
            SettingsItem(
                label = "VERSION",
                value = com.vega.sting.BuildConfig.VERSION_NAME +
                    if (com.vega.sting.BuildConfig.IS_LOCAL_BUILD) "  ·  LOCAL" else ""
            )
            SettingsItem(label = "BUILD", value = com.vega.sting.BuildConfig.VERSION_CODE.toString())
            if (com.vega.sting.BuildConfig.IS_LOCAL_BUILD) {
                Text(
                    text = "Local build — debug-signed, not a published release.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
            }
            SettingsItem(
                label = "CHECK FOR UPDATES",
                value = if (isRecording) "RECORDING…" else "TAP TO CHECK",
                onClick = if (isRecording) null else {
                    {
                        onCheckForUpdates()
                        Toast.makeText(context, "Checking for updates…", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    pendingChannel?.let { target ->
        val current = selectedChannel
        AlertDialog(
            onDismissRequest = { pendingChannel = null },
            title = { Text(stringResource(R.string.update_channel_switch_title), color = AccentYellow) },
            text = {
                Text(
                    buildString {
                        append("You are switching from ${current.label} to ${target.label}. ")
                        if (target == UpdateChannel.DEBUG) {
                            append("DEBUG builds may contain unfinished features and will replace your current installation. ")
                        }
                        append("VEGA STING will check for the latest compatible ${target.label} build. Continue?")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingChannel = null
                    scope.launch {
                        settingsManager.updateSelectedUpdateChannel(target)
                        onCheckForUpdates()
                    }
                    Toast.makeText(context, "Checking ${target.label} channel…", Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.update_channel_switch_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingChannel = null }) {
                    Text(stringResource(R.string.update_channel_switch_cancel))
                }
            }
        )
    }

    openLegalDocument?.let { document ->
        LegalDocumentDialog(document = document, onDismiss = { openLegalDocument = null })
    }

    when (activeDialog) {
        SettingsDialog.VIDEO_CODEC -> SelectionDialog(
            title = "VIDEO CODEC",
            options = VIDEO_CODEC_OPTIONS.map { SelectionOption(it, it) },
            selected = videoCodec,
            onDismiss = { activeDialog = null },
            onSelect = { scope.launch { settingsManager.updateVideoCodec(it) }; activeDialog = null }
        )

        SettingsDialog.VIDEO_SIZE -> SelectionDialog(
            title = "VIDEO SIZE",
            options = VIDEO_SIZE_OPTIONS.map { SelectionOption(it, it) },
            selected = videoSize,
            onDismiss = { activeDialog = null },
            onSelect = { scope.launch { settingsManager.updateResolution(it) }; activeDialog = null }
        )

        SettingsDialog.VIDEO_RATIO -> SelectionDialog(
            title = "VIDEO RATIO",
            options = VIDEO_RATIO_OPTIONS.map { SelectionOption(it, it) },
            selected = videoRatio,
            onDismiss = { activeDialog = null },
            onSelect = { scope.launch { settingsManager.updateVideoRatio(it) }; activeDialog = null }
        )

        SettingsDialog.ORIENTATION -> SelectionDialog(
            title = "ORIENTATION",
            options = ORIENTATION_OPTIONS.map { SelectionOption(it, orientationLabel(it)) },
            selected = orientationMode,
            onDismiss = { activeDialog = null },
            onSelect = { scope.launch { settingsManager.updateOrientationMode(it) }; activeDialog = null }
        )

        SettingsDialog.VIDEO_LIMIT -> SelectionDialog(
            title = "VIDEO FILE LIMIT",
            options = VIDEO_SEGMENT_OPTIONS.map {
                SelectionOption(it, StorageHealthManager.formatFileSize(it))
            },
            selected = videoLimit,
            onDismiss = { activeDialog = null },
            onSelect = {
                scope.launch { settingsManager.updateVideoSegmentLimitBytes(it) }
                activeDialog = null
            }
        )

        SettingsDialog.AUDIO_LIMIT -> SelectionDialog(
            title = "AUDIO FILE LIMIT",
            options = AUDIO_SEGMENT_OPTIONS.map {
                SelectionOption(it, StorageHealthManager.formatFileSize(it))
            },
            selected = audioLimit,
            onDismiss = { activeDialog = null },
            onSelect = {
                scope.launch { settingsManager.updateAudioSegmentLimitBytes(it) }
                activeDialog = null
            }
        )

        SettingsDialog.RESERVE -> SelectionDialog(
            title = "FREE-SPACE RESERVE",
            options = RESERVE_OPTIONS.map {
                SelectionOption(it, StorageHealthManager.formatFileSize(it))
            },
            selected = reserveBytes,
            onDismiss = { activeDialog = null },
            onSelect = {
                scope.launch { settingsManager.updateMinimumFreeSpaceBytes(it) }
                activeDialog = null
            }
        )

        SettingsDialog.TIMESTAMP_POSITION -> SelectionDialog(
            title = "TIMESTAMP POSITION",
            options = OVERLAY_POSITION_OPTIONS.map { SelectionOption(it.name, it.displayName()) },
            selected = timestampPosition,
            onDismiss = { activeDialog = null },
            onSelect = {
                scope.launch { settingsManager.updateTimestampPosition(it) }
                activeDialog = null
            }
        )

        SettingsDialog.TIMESTAMP_FORMAT -> SelectionDialog(
            title = "TIMESTAMP FORMAT",
            options = TIMESTAMP_FORMAT_OPTIONS.map { SelectionOption(it, it) },
            selected = timestampFormat,
            onDismiss = { activeDialog = null },
            onSelect = {
                scope.launch { settingsManager.updateTimestampFormat(it) }
                activeDialog = null
            }
        )

        SettingsDialog.SIGNATURE_POSITION -> SelectionDialog(
            title = "SIGNATURE POSITION",
            options = OVERLAY_POSITION_OPTIONS.map { SelectionOption(it.name, it.displayName()) },
            selected = signaturePosition,
            onDismiss = { activeDialog = null },
            onSelect = {
                scope.launch { settingsManager.updateSignaturePosition(it) }
                activeDialog = null
            }
        )

        SettingsDialog.TEXT_SIZE -> SelectionDialog(
            title = "TEXT SIZE",
            options = TEXT_SIZE_OPTIONS.map { SelectionOption(it, it.toString()) },
            selected = overlayTextSize,
            onDismiss = { activeDialog = null },
            onSelect = {
                scope.launch { settingsManager.updateOverlayTextSize(it) }
                activeDialog = null
            }
        )

        SettingsDialog.POWER_ACTION -> SelectionDialog(
            title = "POWER ACTION",
            options = POWER_ACTION_OPTIONS.map { SelectionOption(it.first, it.second) },
            selected = powerMode,
            onDismiss = { activeDialog = null },
            onSelect = {
                scope.launch { settingsManager.updatePowerButtonMode(it) }
                activeDialog = null
            }
        )

        SettingsDialog.STORAGE -> SelectionDialog(
            title = "STORAGE LOCATION",
            options = availableStorages.map { (type, dir) ->
                val free = StorageHealthManager.formatBytes(
                    StorageHealthManager.getFreeSpaceBytes(dir)
                )
                SelectionOption(
                    value = type.name,
                    label = type.name,
                    detail = if (type.name == storageLocation) "$free  *" else free
                )
            },
            selected = storageLocation,
            onDismiss = { activeDialog = null },
            onSelect = {
                scope.launch { settingsManager.updateStorageLocation(it) }
                activeDialog = null
            },
            footnote = if (availableStorages.size == 1) "No removable storage detected." else null
        )

        null -> Unit
    }
}

private enum class SettingsDialog {
    VIDEO_CODEC, VIDEO_SIZE, VIDEO_RATIO, ORIENTATION,
    VIDEO_LIMIT, AUDIO_LIMIT, RESERVE,
    TIMESTAMP_POSITION, TIMESTAMP_FORMAT, SIGNATURE_POSITION, TEXT_SIZE,
    POWER_ACTION, STORAGE
}

private val VIDEO_CODEC_OPTIONS = listOf("H.264", "H.265")
private val VIDEO_SIZE_OPTIONS = listOf("AUTO", "480P", "720P", "1080P", "1440P", "4K")
private val VIDEO_RATIO_OPTIONS = listOf("AUTO", "16:9", "4:3", "1:1", "DISPLAY")
private val ORIENTATION_OPTIONS = listOf("PORTRAIT", "LANDSCAPE", "FOLLOW_SENSOR")
private val TIMESTAMP_FORMAT_OPTIONS = listOf("yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss")
private val TEXT_SIZE_OPTIONS = listOf(14, 16, 18, 20, 22, 24, 26, 28)
private val POWER_ACTION_OPTIONS = listOf("VIDEO" to "TOGGLE AV", "AUDIO" to "TOGGLE AUDIO")
private val OVERLAY_POSITION_OPTIONS = OverlayPosition.entries.toList()

private const val MB = 1024L * 1024L
private const val GB = 1024L * MB

private val VIDEO_SEGMENT_OPTIONS = listOf(500L * MB, 1L * GB, 2L * GB, 4L * GB)
private val AUDIO_SEGMENT_OPTIONS = listOf(50L * MB, 100L * MB, 250L * MB, 500L * MB)
private val RESERVE_OPTIONS = listOf(100L * MB, 300L * MB, 500L * MB, 1L * GB)

/** Friendly display for a stored orientation mode. Storage keeps the raw value. */
private fun orientationLabel(mode: String): String = when (mode) {
    "FOLLOW_SENSOR" -> "FOLLOW SENSOR"
    else -> mode
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, color = AccentYellow)
        HorizontalDivider(modifier = Modifier.padding(top = 2.dp, bottom = 2.dp), thickness = 0.5.dp, color = Border)
        content()
    }
}

@Composable
fun SettingsItem(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    @DrawableRes valueIcon: Int? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.48f),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(12.dp))
        Row(
            modifier = Modifier.weight(0.52f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (valueIcon != null) {
                val valueColor = if (onClick != null) AccentOrange else TextPrimary
                Text(
                    text = "[",
                    style = MaterialTheme.typography.bodySmall,
                    color = valueColor,
                    maxLines = 1
                )
                Image(
                    painter = painterResource(valueIcon),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$value]",
                    style = MaterialTheme.typography.bodySmall,
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = "[ $value ]",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (onClick != null) AccentOrange else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    val target = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open link", Toast.LENGTH_SHORT).show()
    }
}
