package com.vega.sting

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vega.sting.core.RecordingState
import com.vega.sting.core.RecordingStateManager
import com.vega.sting.legal.LegalConsentStore
import com.vega.sting.services.RecordingService
import com.vega.sting.services.ServiceUtils
import com.vega.sting.ui.components.StatusPanel
import com.vega.sting.ui.components.TerminalButton
import com.vega.sting.ui.legal.WelcomeConsentScreen
import com.vega.sting.ui.settings.SettingsScreen
import com.vega.sting.ui.trash.TrashScreen
import com.vega.sting.ui.update.UpdateHost
import com.vega.sting.ui.theme.*
import com.vega.sting.ui.viewmodel.RecordingViewModel
import com.vega.sting.ui.playback.PlaybackScreen
import androidx.compose.ui.draw.alpha
import com.vega.sting.settings.SettingsManager
import com.vega.sting.storage.StorageManager
import com.vega.sting.storage.StorageType
import com.vega.sting.storage.StorageHealthManager
import com.vega.sting.ui.dialogs.LowStorageDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            VEGASTINGTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Background
                ) {
                    MainContent()
                }
            }
        }
    }
}

@Composable
fun MainContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val consentStore = remember { LegalConsentStore(context) }
    val consent by consentStore.consent.collectAsState(initial = null)

    when {
        
        
        consent == null -> ConsentLoadingScreen()
        !consent!!.isComplete -> WelcomeConsentScreen(
            onAccept = { scope.launch { consentStore.accept() } }
        )
        else -> AcceptedAppContent()
    }
}

@Composable
private fun ConsentLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.legal_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun AcceptedAppContent(viewModel: RecordingViewModel = viewModel()) {
    var currentScreen by remember { mutableStateOf("home") }
    var selectedPlaybackRecording by remember { mutableStateOf<com.vega.sting.database.Recording?>(null) }

    
    
    var updateCheckToken by remember { mutableIntStateOf(0) }

    
    
    
    LaunchedEffect(currentScreen) { viewModel.clearSelection() }

    
    
    
    BackHandler(enabled = currentScreen != "home") {
        currentScreen = "home"
    }

    when (currentScreen) {
        "home" -> HomeScreen(
            viewModel, 
            onNavigateToSettings = { currentScreen = "settings" },
            onNavigateToTrash = { currentScreen = "trash" },
            onOpenPlayback = { recording ->
                selectedPlaybackRecording = recording
                currentScreen = "playback"
            }
        )
        "settings" -> SettingsScreen(
            onBack = { currentScreen = "home" },
            onCheckForUpdates = { updateCheckToken++ }
        )
        "trash" -> TrashScreen(viewModel, onBack = { currentScreen = "home" })
        "playback" -> {
            selectedPlaybackRecording?.let {
                PlaybackScreen(recording = it, onBack = { currentScreen = "home" })
            } ?: run {
                currentScreen = "home"
            }
        }
    }

    
    
    
    
    
    val recordingState by RecordingStateManager.state.collectAsState()
    val autoCheckAllowed = recordingState == RecordingState.IDLE ||
        recordingState == RecordingState.ERROR
    UpdateHost(
        manualCheckToken = updateCheckToken,
        autoCheckEnabled = autoCheckAllowed
    )
}

private val TableIndexWeight = 0.05f
private val TableNameWeight = 0.55f
private val TableTimeWeight = 0.25f
private val TableSelectWeight = 0.15f

@Composable
fun HomeScreen(
    viewModel: RecordingViewModel, 
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onOpenPlayback: (com.vega.sting.database.Recording) -> Unit
) {
    val context = LocalContext.current
    val recordings by viewModel.allRecordings.collectAsState(initial = emptyList())
    val selectedIds by viewModel.selectedIds.collectAsState()
    val selectionMode = selectedIds.isNotEmpty()
    val recordingState by RecordingStateManager.state.collectAsState()
    val duration by RecordingStateManager.duration.collectAsState()
    val currentFileName by RecordingStateManager.currentFileName.collectAsState()
    val currentCodec by RecordingStateManager.currentCodec.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var currentFilter by remember { mutableStateOf("ALL") }
    var showLowStorageDialog by remember { mutableStateOf(false) }
    var freeSpaceStr by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val settingsManager = remember { SettingsManager(context) }
    val storageLocation by settingsManager.storageLocation.collectAsState(initial = "INTERNAL")
    val resolution by settingsManager.resolution.collectAsState(initial = "AUTO")
    val fps by settingsManager.fps.collectAsState(initial = 30)
    val videoCodec by settingsManager.videoCodec.collectAsState(initial = "H.265")
    val audioCodec by settingsManager.audioCodec.collectAsState(initial = "AAC")
    val persistedSortTypeStr by settingsManager.sortType.collectAsState(initial = "DATE")
    val persistedSortOrderStr by settingsManager.sortOrder.collectAsState(initial = "DESC")

    val sortType = remember(persistedSortTypeStr) {
        try { SortType.valueOf(persistedSortTypeStr) } catch (e: Exception) { SortType.DATE }
    }
    val sortOrder = remember(persistedSortOrderStr) {
        try { SortOrder.valueOf(persistedSortOrderStr) } catch (e: Exception) { SortOrder.DESC }
    }
    var showSortMenu by remember { mutableStateOf(false) }

    var freeSpace by remember { mutableStateOf("0.00 GB") }

    LaunchedEffect(storageLocation, recordingState) {
        while (true) {
            val storageType = when (storageLocation) {
                "SD_CARD" -> StorageType.SD_CARD
                "OTG" -> StorageType.OTG
                else -> StorageType.INTERNAL
            }
            val dir = StorageManager.getRecordingDirectory(context, storageType)
            val freeBytes = StorageHealthManager.getFreeSpaceBytes(dir)
            freeSpace = StorageHealthManager.formatBytes(freeBytes)
            kotlinx.coroutines.delay(2000)
        }
    }

    val filteredRecordings = recordings
        .filter {
            (currentFilter == "ALL" || it.type.name == currentFilter) &&
            (searchQuery.isEmpty() || it.name.contains(searchQuery, ignoreCase = true))
        }
        .sortedWith(
            when (sortType) {
                SortType.DATE -> compareBy<com.vega.sting.database.Recording> { it.timestamp }
                SortType.NAME -> compareBy<com.vega.sting.database.Recording> { it.name.lowercase() }
                SortType.SIZE -> compareBy<com.vega.sting.database.Recording> { it.size }
                SortType.TYPE -> compareBy<com.vega.sting.database.Recording> { it.type.name }
            }
        )
        .let { list ->
            if (sortOrder == SortOrder.DESC) list.reversed() else list
        }
    
    val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (!results.all { it.value }) {
            Toast.makeText(context, "Critical Permissions Denied", Toast.LENGTH_LONG).show()
        }
    }

    
    
    
    
    
    var permissionsRequested by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!permissionsRequested) {
            permissionsRequested = true

            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                launcher.launch(missing.toTypedArray())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
            ) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse(String.format("package:%s", context.packageName))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    context.startActivity(intent)
                }
            }
        }
    }

    if (showLowStorageDialog) {
        LowStorageDialog(
            freeSpace = freeSpaceStr,
            onContinueVideo = {
                showLowStorageDialog = false
                val intent = Intent(context, RecordingService::class.java).apply { 
                    action = RecordingService.ACTION_START_VIDEO
                    putExtra("IGNORE_STORAGE_WARNING", true)
                }
                context.startForegroundService(intent)
            },
            onSwitchAudio = {
                showLowStorageDialog = false
                val intent = Intent(context, RecordingService::class.java).apply { 
                    action = RecordingService.ACTION_START_AUDIO
                }
                context.startForegroundService(intent)
            },
            onCancel = { showLowStorageDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .safeDrawingPadding()
    ) {
        
        HeaderBar(recordingState)

        
        val modeText = when (recordingState) {
            RecordingState.RECORDING_VIDEO -> "AV RECORDING"
            RecordingState.RECORDING_AUDIO -> "AUDIO RECORDING"
            else -> null
        }
        val timerText = if (modeText != null) {
            val mins = duration / 60
            val secs = duration % 60
            String.format("%02d:%02d", mins, secs)
        } else null

        val displayCodec = when (recordingState) {
            RecordingState.RECORDING_AUDIO, RecordingState.STARTING_AUDIO, RecordingState.SWITCHING_TO_AUDIO ->
                currentCodec ?: audioCodec
            RecordingState.RECORDING_VIDEO, RecordingState.STARTING_VIDEO, RecordingState.SWITCHING_TO_VIDEO ->
                currentCodec ?: videoCodec
            else ->
                "$videoCodec/$audioCodec"
        }

        val storageLabel = when (storageLocation) {
            "SD_CARD" -> "SD CARD"
            "OTG" -> "OTG"
            else -> "INTERNAL"
        }

        val displayResolution = if (recordingState == RecordingState.RECORDING_AUDIO ||
            recordingState == RecordingState.STARTING_AUDIO ||
            recordingState == RecordingState.SWITCHING_TO_AUDIO) {
            "---"
        } else {
            resolution
        }

        val displayFps = if (recordingState == RecordingState.RECORDING_AUDIO ||
            recordingState == RecordingState.STARTING_AUDIO ||
            recordingState == RecordingState.SWITCHING_TO_AUDIO) {
            "---"
        } else {
            fps.toString()
        }

        StatusPanel(
            mode = modeText,
            storageLocation = storageLabel,
            freeSpace = freeSpace,
            codec = displayCodec,
            resolution = displayResolution,
            fps = displayFps,
            timer = timerText,
            fileName = currentFileName
        )

        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(text = "ALL", active = currentFilter == "ALL", onClick = { currentFilter = "ALL" })
            FilterChip(text = "VIDEO", active = currentFilter == "VIDEO", onClick = { currentFilter = "VIDEO" })
            FilterChip(text = "AUDIO", active = currentFilter == "AUDIO", onClick = { currentFilter = "AUDIO" })
            
            Spacer(modifier = Modifier.weight(1f))
            
            Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                Text(
                    text = "SORT: ${sortType.name} ${if (sortOrder == SortOrder.DESC) "▼" else "▲"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentYellow,
                    modifier = Modifier.clickable { showSortMenu = true }
                )

                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                    modifier = Modifier.background(Panel).border(0.5.dp, Border)
                ) {
                    SortType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    text = type.name, 
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (sortType == type) AccentOrange else TextPrimary
                                ) 
                            },
                            onClick = {
                                scope.launch {
                                    val newOrder = if (sortType == type) {
                                        if (sortOrder == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
                                    } else {
                                        SortOrder.DESC
                                    }
                                    settingsManager.updateSort(type.name, newOrder.name)
                                }
                                showSortMenu = false
                            }
                        )
                    }
                }
            }
        }

        
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            placeholder = { Text("SEARCH RECORDINGS...", style = MaterialTheme.typography.bodySmall, color = TextSecondary) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentOrange,
                unfocusedBorderColor = Border,
                cursorColor = AccentOrange
            )
        )

        
        val allSelected = filteredRecordings.isNotEmpty() && filteredRecordings.all { selectedIds.contains(it.id) }
        RecordingTableHeader(
            selectionMode = selectionMode,
            allSelected = allSelected,
            onSelectAll = {
                val visibleIds = filteredRecordings.map { it.id }
                if (allSelected) viewModel.clearSelection() else viewModel.selectAll(visibleIds)
            }
        )

        
        LazyColumn(modifier = Modifier.weight(1f)) {
            
            if (recordingState == RecordingState.RECORDING_VIDEO || recordingState == RecordingState.RECORDING_AUDIO) {
                item(key = "live_recording") {
                    RecordingRow(
                        index = 0,
                        recording = com.vega.sting.database.Recording(
                            name = currentFileName ?: "INITIALIZING...",
                            timestamp = System.currentTimeMillis(),
                            path = "",
                            type = if (recordingState == RecordingState.RECORDING_AUDIO) com.vega.sting.database.RecordingType.AUDIO else com.vega.sting.database.RecordingType.VIDEO,
                            codec = currentCodec ?: "---",
                            size = 0
                        ),
                        isSelected = false,
                        isLive = true,
                        selectionMode = selectionMode,
                        onToggleSelection = {},
                        onOpen = {}
                    )
                }
            }

            itemsIndexed(
                items = filteredRecordings,
                
                
                
                key = { _, recording -> recording.id }
            ) { index, recording ->
                RecordingRow(
                    index = index + 1, 
                    recording = recording,
                    isSelected = selectedIds.contains(recording.id),
                    selectionMode = selectionMode,
                    onToggleSelection = { viewModel.toggleSelection(recording.id) },
                    onOpen = {
                        onOpenPlayback(recording)
                    }
                )
            }
        }

        
        ActionBar(
            recordingState = recordingState,
            selectedCount = selectedIds.size,
            onStartVideo = {
                scope.launch {
                    val location = settingsManager.storageLocation.first()
                    val storageType = when(location) {
                        "SD_CARD" -> StorageType.SD_CARD
                        "OTG" -> StorageType.OTG
                        else -> StorageType.INTERNAL
                    }
                    val storageDir = StorageManager.getRecordingDirectory(context, storageType)
                    val freeBytes = StorageHealthManager.getFreeSpaceBytes(storageDir)

                    if (freeBytes <= StorageHealthManager.WARNING_LEVEL) {
                        freeSpaceStr = StorageHealthManager.formatBytes(freeBytes)
                        showLowStorageDialog = true
                    } else {
                        val intent = Intent(context, RecordingService::class.java).apply { 
                            action = RecordingService.ACTION_START_VIDEO
                        }
                        context.startForegroundService(intent)
                    }
                }
            },
            onStartAudio = {
                val intent = Intent(context, RecordingService::class.java).apply { 
                    action = RecordingService.ACTION_START_AUDIO
                }
                context.startForegroundService(intent)
            },
            onStop = {
                val intent = Intent(context, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_STOP
                }
                context.startForegroundService(intent)
            },
            onDelete = {
                viewModel.softDeleteAll(selectedIds)
                viewModel.clearSelection()
            },
            onShare = {
                val selected = viewModel.getSelectedRecordings(recordings)
                com.vega.sting.share.ShareManager.shareRecordings(context, selected)
                viewModel.clearSelection()
            },
            onNavigateToTrash = onNavigateToTrash,
            onNavigateToSettings = onNavigateToSettings
        )
    }
}

@Composable
fun ActionBar(
    recordingState: RecordingState,
    selectedCount: Int,
    onStartVideo: () -> Unit,
    onStartAudio: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(
                start = 8.dp,
                end = 8.dp,
                top = 8.dp,
                bottom = 12.dp
            ),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (selectedCount > 0) {
            TerminalButton(text = "Share", onClick = onShare, modifier = Modifier.weight(1f))
            TerminalButton(text = "Delete", onClick = onDelete, isStopMode = true, modifier = Modifier.weight(1f))
        } else {
            when (recordingState) {
                RecordingState.IDLE, RecordingState.ERROR -> {
                    TerminalButton(text = "Video", onClick = onStartVideo, modifier = Modifier.weight(1f))
                    TerminalButton(text = "Audio", onClick = onStartAudio, modifier = Modifier.weight(1f))
                    TerminalButton(text = "Trash", onClick = onNavigateToTrash, modifier = Modifier.weight(1f))
                    TerminalButton(text = "Setup", onClick = onNavigateToSettings, modifier = Modifier.weight(1f))
                }
                RecordingState.RECORDING_VIDEO, RecordingState.STARTING_VIDEO, RecordingState.SWITCHING_TO_VIDEO -> {
                    TerminalButton(text = "Stop", onClick = onStop, isStopMode = true, modifier = Modifier.weight(1f))
                    TerminalButton(text = "Audio", onClick = onStartAudio, modifier = Modifier.weight(1f))
                    TerminalButton(text = "Trash", onClick = onNavigateToTrash, modifier = Modifier.weight(1f))
                    TerminalButton(text = "Setup", onClick = onNavigateToSettings, modifier = Modifier.weight(1f))
                }
                RecordingState.RECORDING_AUDIO, RecordingState.STARTING_AUDIO, RecordingState.SWITCHING_TO_AUDIO -> {
                    TerminalButton(text = "Stop", onClick = onStop, isStopMode = true, modifier = Modifier.weight(1f))
                    TerminalButton(text = "Video", onClick = onStartVideo, modifier = Modifier.weight(1f))
                    TerminalButton(text = "Trash", onClick = onNavigateToTrash, modifier = Modifier.weight(1f))
                    TerminalButton(text = "Setup", onClick = onNavigateToSettings, modifier = Modifier.weight(1f))
                }
                
                
                
                RecordingState.STOPPING -> {
                    TerminalButton(text = "Stopping...", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun HeaderBar(recordingState: RecordingState) {
    val isRecording = recordingState != RecordingState.IDLE && recordingState != RecordingState.ERROR
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Background)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "VEGA STING",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        if (isRecording) {
            val infiniteTransition = rememberInfiniteTransition(label = "recording")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusText = when (recordingState) {
                    RecordingState.STARTING_VIDEO, RecordingState.SWITCHING_TO_VIDEO -> "STARTING VIDEO..."
                    RecordingState.STARTING_AUDIO, RecordingState.SWITCHING_TO_AUDIO -> "STARTING AUDIO..."
                    RecordingState.RECORDING_VIDEO -> "REC ● VIDEO"
                    RecordingState.RECORDING_AUDIO -> "REC ● AUDIO"
                    RecordingState.STOPPING -> "STOPPING..."
                    else -> ""
                }
                Text(
                    text = statusText,
                    color = Error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.alpha(alpha)
                )
            }
        }
    }
}

@Composable
fun FilterChip(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = if (active) "[ $text ]" else text,
        style = MaterialTheme.typography.bodySmall,
        color = if (active) AccentOrange else TextSecondary,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun RecordingTableHeader(
    selectionMode: Boolean,
    allSelected: Boolean,
    onSelectAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(Panel)
            .border(0.5.dp, Border)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#",
            modifier = Modifier.weight(TableIndexWeight),
            style = MaterialTheme.typography.bodySmall,
            color = AccentYellow
        )
        Text(
            text = "NAME",
            modifier = Modifier.weight(TableNameWeight),
            style = MaterialTheme.typography.bodySmall,
            color = AccentYellow
        )
        Text(
            text = "TIME",
            modifier = Modifier.weight(TableTimeWeight),
            style = MaterialTheme.typography.bodySmall,
            color = AccentYellow
        )
        Text(
            text = if (selectionMode) (if (allSelected) "CLEAR" else "SELECT") else "SEL",
            modifier = Modifier
                .weight(TableSelectWeight)
                .clickable(enabled = selectionMode) { onSelectAll() },
            style = MaterialTheme.typography.bodySmall,
            color = AccentYellow,
            textAlign = TextAlign.End
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RecordingRow(
    index: Int, 
    recording: com.vega.sting.database.Recording,
    isSelected: Boolean,
    isLive: Boolean = false,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onOpen: () -> Unit
) {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val timeStr = sdf.format(Date(recording.timestamp))
    val sizeStr = if (isLive) "--" else StorageHealthManager.formatFileSize(recording.size)
    val isAudio = recording.type == com.vega.sting.database.RecordingType.AUDIO

    
    
    
    val resolutionStr = when {
        isLive -> "---"
        isAudio -> "AUDIO"
        recording.hasKnownResolution -> recording.resolutionLabel
        else -> "UNKNOWN"
    }

    val liveAlpha by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(0.5.dp, if (isSelected) AccentOrange else if (isLive) Error else Border)
            .background(if (isSelected) Panel else Background)
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onToggleSelection()
                    } else {
                        onOpen()
                    }
                },
                onLongClick = {
                    onToggleSelection()
                }
            )
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isLive) "" else index.toString(),
                modifier = Modifier.weight(TableIndexWeight),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Row(
                modifier = Modifier.weight(TableNameWeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(
                        if (isAudio) R.drawable.ic_mic else R.drawable.ic_videocam
                    ),
                    contentDescription = null,
                    tint = if (isSelected) AccentOrange else TextSecondary,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = 4.dp)
                )
                Text(
                    text = recording.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isLive) Error else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = timeStr,
                modifier = Modifier.weight(TableTimeWeight),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1
            )

            Box(
                modifier = Modifier.weight(TableSelectWeight),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (isLive) {
                    Text(
                        text = "● LIVE",
                        color = Error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(liveAlpha)
                    )
                } else {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection() },
                        modifier = Modifier.size(18.dp),
                        colors = CheckboxDefaults.colors(
                            checkedColor = AccentOrange,
                            uncheckedColor = Border,
                            checkmarkColor = Background
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.weight(TableIndexWeight))
            Text(
                text = "${recording.codec} | $resolutionStr | $sizeStr",
                modifier = Modifier
                    .weight(TableNameWeight)
                    .padding(start = 22.dp),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.weight(TableTimeWeight + TableSelectWeight))
        }
    }
}

enum class SortType {
    DATE,
    NAME,
    SIZE,
    TYPE
}

enum class SortOrder {
    ASC,
    DESC
}
