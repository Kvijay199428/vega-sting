package com.vega.sting.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.vega.sting.core.RecordingStateManager
import com.vega.sting.legal.LegalDocument
import com.vega.sting.overlay.OverlayPosition
import com.vega.sting.settings.SettingsManager
import com.vega.sting.ui.components.DetailHeaderBar
import com.vega.sting.ui.legal.LegalDocumentDialog
import com.vega.sting.storage.StorageHealthManager
import com.vega.sting.storage.StorageManager
import com.vega.sting.storage.StorageType
import com.vega.sting.ui.theme.*
import kotlinx.coroutines.launch

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
    
    
    
    
    
    var availableStorages by remember { mutableStateOf(StorageManager.getAvailableStorages(context)) }
    var showStoragePicker by remember { mutableStateOf(false) }
    var showSignatureEditor by remember { mutableStateOf(false) }
    var signatureDraft by remember { mutableStateOf(signature) }

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

    if (showStoragePicker) {
        AlertDialog(
            onDismissRequest = { showStoragePicker = false },
            title = { Text("STORAGE LOCATION", color = AccentYellow) },
            text = {
                Column {
                    availableStorages.forEach { (type, dir) ->
                        val name = type.name
                        val free = StorageHealthManager.formatBytes(
                            StorageHealthManager.getFreeSpaceBytes(dir)
                        )
                        SettingsItem(
                            label = name,
                            value = if (name == storageLocation) "$free  *" else free,
                            onClick = {
                                scope.launch { settingsManager.updateStorageLocation(name) }
                                showStoragePicker = false
                            }
                        )
                    }
                    if (availableStorages.size == 1) {
                        Text(
                            "No removable storage detected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStoragePicker = false }) {
                    Text("CLOSE", color = TextSecondary)
                }
            },
            containerColor = Panel,
            shape = MaterialTheme.shapes.extraSmall
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
                onClick = {
                    val next = if (videoCodec == "H.264") "H.265" else "H.264"
                    scope.launch { settingsManager.updateVideoCodec(next) }
                }
            )
            SettingsItem(
                label = "VIDEO SIZE",
                value = videoSize,
                onClick = {
                    val next = cycleOption(VIDEO_SIZE_OPTIONS, videoSize)
                    scope.launch { settingsManager.updateResolution(next) }
                }
            )
            SettingsItem(
                label = "VIDEO RATIO",
                value = videoRatio,
                onClick = {
                    val next = cycleOption(VIDEO_RATIO_OPTIONS, videoRatio)
                    scope.launch { settingsManager.updateVideoRatio(next) }
                }
            )
            
            
            
            SettingsItem(
                label = "ORIENTATION", 
                value = orientationMode,
                onClick = {
                    val next = when(orientationMode) {
                        "PORTRAIT" -> "LANDSCAPE"
                        "LANDSCAPE" -> "FOLLOW_SENSOR"
                        else -> "PORTRAIT"
                    }
                    scope.launch { settingsManager.updateOrientationMode(next) }
                }
            )
        }

        SettingsSection(title = "OVERLAY") {
            SettingsItem(
                label = "TIMESTAMP",
                value = if (timestampEnabled) "ENABLED" else "DISABLED",
                onClick = {
                    scope.launch { settingsManager.updateTimestampEnabled(!timestampEnabled) }
                }
            )
            SettingsItem(
                label = "TIMESTAMP POSITION",
                value = OverlayPosition.from(timestampPosition).displayName(),
                onClick = {
                    scope.launch {
                        settingsManager.updateTimestampPosition(nextOverlayPosition(timestampPosition))
                    }
                }
            )
            SettingsItem(
                label = "TIMESTAMP FORMAT",
                value = timestampFormat,
                onClick = {
                    val next = if (timestampFormat == "yyyy-MM-dd HH:mm:ss") {
                        "yyyy/MM/dd HH:mm:ss"
                    } else {
                        "yyyy-MM-dd HH:mm:ss"
                    }
                    scope.launch { settingsManager.updateTimestampFormat(next) }
                }
            )
            SettingsItem(
                label = "SIGNATURE",
                value = if (signature.isBlank()) "OFF" else signature,
                onClick = { showSignatureEditor = true }
            )
            SettingsItem(
                label = "WATERMARK",
                value = if (watermarkEnabled) "ENABLED" else "DISABLED",
                onClick = {
                    scope.launch { settingsManager.updateWatermark(!watermarkEnabled) }
                }
            )
            SettingsItem(
                label = "SIGNATURE POSITION",
                value = OverlayPosition.from(signaturePosition).displayName(),
                onClick = {
                    scope.launch {
                        settingsManager.updateSignaturePosition(nextOverlayPosition(signaturePosition))
                    }
                }
            )
            SettingsItem(
                label = "TEXT SIZE",
                value = overlayTextSize.toString(),
                onClick = {
                    val next = if (overlayTextSize >= 28) 14 else overlayTextSize + 2
                    scope.launch { settingsManager.updateOverlayTextSize(next) }
                }
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
                onClick = { showStoragePicker = true }
            )
            SettingsItem(label = "DIRECTORY", value = "VEGA STING")
        }

        SettingsSection(title = "TRASH") {
            SettingsItem(label = "TRASH SIZE", value = trashSizeStr)
            SettingsItem(label = "AUTO CLEANUP", value = "30 DAYS")
        }

        SettingsSection(title = "HARDWARE") {
            SettingsItem(
                label = "POWER ACTION", 
                value = if (powerMode == "VIDEO") "TOGGLE AV" else "TOGGLE AUDIO",
                onClick = {
                    val next = if (powerMode == "VIDEO") "AUDIO" else "VIDEO"
                    scope.launch { settingsManager.updatePowerButtonMode(next) }
                }
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

        SettingsSection(title = "ABOUT") {
            SettingsItem(label = "DEVELOPER", value = com.vega.sting.BuildConfig.DEV_NAME)
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
            SettingsItem(label = "VERSION", value = com.vega.sting.BuildConfig.VERSION_NAME)
            SettingsItem(label = "BUILD", value = com.vega.sting.BuildConfig.VERSION_CODE.toString())
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

    openLegalDocument?.let { document ->
        LegalDocumentDialog(document = document, onDismiss = { openLegalDocument = null })
    }
}

private val VIDEO_SIZE_OPTIONS = listOf("AUTO", "480P", "720P", "1080P", "1440P", "4K")
private val VIDEO_RATIO_OPTIONS = listOf("AUTO", "16:9", "4:3", "1:1", "DISPLAY")

private fun cycleOption(options: List<String>, current: String): String {
    val index = options.indexOf(current)
    return if (index < 0) options.first() else options[(index + 1) % options.size]
}

private fun nextOverlayPosition(current: String): String {
    val positions = OverlayPosition.entries
    val currentIndex = positions.indexOf(OverlayPosition.from(current))
    return positions[(currentIndex + 1) % positions.size].name
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
fun SettingsItem(label: String, value: String, onClick: (() -> Unit)? = null) {
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
        Text(
            text = "[ $value ]",
            modifier = Modifier.weight(0.52f),
            style = MaterialTheme.typography.bodySmall,
            color = if (onClick != null) AccentOrange else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End
        )
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
