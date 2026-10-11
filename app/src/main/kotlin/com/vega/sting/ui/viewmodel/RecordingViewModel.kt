package com.vega.sting.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vega.sting.database.AppDatabase
import com.vega.sting.database.Recording
import com.vega.sting.database.RecordingDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import com.vega.sting.repository.RecordingRepository
import android.widget.Toast

class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "RecordingViewModel"

        /** Matches `[stem]_part-N.(mp4|m4a)` files written by the split recorder. */
        private val PART_FILE_PATTERN = Regex("""^(.+)_part-(\d+)\.(mp4|m4a)$""")
    }

    private val recordingDao: RecordingDao = AppDatabase.getDatabase(application).recordingDao()
    private val repository = RecordingRepository(application, recordingDao)
    val allRecordings: Flow<List<Recording>> = recordingDao.getAllActiveRecordings()
    val trashRecordings: Flow<List<Recording>> = recordingDao.getTrashRecordings()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            recoverInterruptedSegments()
            importOrphanSegments()
            reconcileMissingActiveRows()
            com.vega.sting.storage.TrashRetentionManager.cleanupOldTrash(application)
        }
        com.vega.sting.storage.TrashRetentionWorker.schedule(application)
    }

    /**
     * Rebuilds the manifest from the database and the filesystem. A part file
     * that exists on disk but has no row (for example if the app died between
     * opening the file and writing the row) is imported as a failed segment
     * rather than being silently lost.
     */
    private suspend fun importOrphanSegments() {
        if (com.vega.sting.core.RecordingStateManager.isRecording()) return
        try {
            val known = recordingDao.getAllPaths().toHashSet()
            com.vega.sting.storage.StorageManager
                .getAvailableStorages(getApplication())
                .forEach storageLoop@{ (_, directory) ->
                    val files = directory.listFiles() ?: return@storageLoop
                    files.filter { it.isFile && it.length() > 0L && PART_FILE_PATTERN.containsMatchIn(it.name) }
                        .forEach { file ->
                            if (known.contains(file.absolutePath)) return@forEach
                            val match = PART_FILE_PATTERN.find(file.name) ?: return@forEach
                            val stem = match.groupValues[1]
                            val part = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: 1
                            val isAudio = file.name.endsWith(".m4a")
                            recordingDao.insertIfAbsent(
                                Recording(
                                    name = file.name,
                                    timestamp = file.lastModified(),
                                    path = file.absolutePath,
                                    type = if (isAudio)
                                        com.vega.sting.database.RecordingType.AUDIO
                                    else
                                        com.vega.sting.database.RecordingType.VIDEO,
                                    codec = if (isAudio) "AAC" else "H.264",
                                    size = file.length(),
                                    sessionId = "recovered:$stem",
                                    partIndex = part,
                                    segmentStatus = com.vega.sting.database.SegmentStatus.FAILED.name,
                                    startedAt = file.lastModified(),
                                    endedAt = file.lastModified(),
                                    finalizationReason =
                                        com.vega.sting.database.FinalizationReason.ERROR.name,
                                    isSessionComplete = true
                                )
                            )
                            Log.w(TAG, "recovery: imported orphan segment ${file.name}")
                        }
                }
        } catch (e: Exception) {
            Log.e(TAG, "orphan import failed", e)
        }
    }

    /**
     * Finalises segments that were still marked RECORDING when the app last
     * stopped (crash, force-kill, battery pull). Skipped entirely while a
     * recording is actually in progress so the live part is never touched.
     */
    private suspend fun recoverInterruptedSegments() {
        if (com.vega.sting.core.RecordingStateManager.isRecording()) return
        try {
            val orphaned = recordingDao.getInProgressSegments()
            orphaned.forEach { rec ->
                val file = File(rec.path)
                if (file.exists() && file.length() > 0L) {
                    Log.w(TAG, "recovery: keeping interrupted segment id=${rec.id} ${rec.path}")
                    if (rec.sessionId != null) {
                        recordingDao.finalizeSegment(
                            sessionId = rec.sessionId,
                            partIndex = rec.partIndex,
                            name = rec.name,
                            path = rec.path,
                            size = file.length(),
                            codec = rec.codec,
                            width = rec.width,
                            height = rec.height,
                            endedAt = rec.endedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                            reason = com.vega.sting.database.FinalizationReason.ERROR.name,
                            status = com.vega.sting.database.SegmentStatus.FAILED.name
                        )
                    } else {
                        recordingDao.setSegmentStatus(
                            rec.id,
                            com.vega.sting.database.SegmentStatus.FAILED.name,
                            com.vega.sting.database.FinalizationReason.ERROR.name
                        )
                    }
                } else {
                    Log.w(TAG, "recovery: dropping empty interrupted segment id=${rec.id} ${rec.path}")
                    recordingDao.delete(rec)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "recovery failed", e)
        }
    }

    private suspend fun reconcileMissingActiveRows() {
        try {
            val actives = recordingDao.getActiveRecordingsSync()
            actives.forEach { rec ->
                if (!File(rec.path).exists()) {
                    Log.w(TAG, "reconcile: removing ghost row id=${rec.id} path=${rec.path}")
                    recordingDao.delete(rec)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "reconcile failed", e)
        }
    }

    private val _selectedIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedIds: StateFlow<Set<Int>> = _selectedIds.asStateFlow()

    fun toggleSelection(id: Int) {
        _selectedIds.value = if (_selectedIds.value.contains(id)) {
            _selectedIds.value - id
        } else {
            _selectedIds.value + id
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun selectAll(ids: List<Int>) {
        _selectedIds.value = ids.toSet()
    }

    fun getSelectedRecordings(
        recordings: List<Recording>
    ): List<Recording> {
        return recordings.filter { _selectedIds.value.contains(it.id) }
    }

    fun insert(recording: Recording) = viewModelScope.launch {
        recordingDao.insert(recording)
    }

    fun softDeleteAll(ids: Set<Int>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            ids.forEach { id ->
                val rec = recordingDao.getById(id)
                if (rec != null) {
                    val ok = repository.softDeleteRecording(rec)
                    if (!ok) failed++
                }
            }
            if (failed > 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        getApplication(),
                        "$failed recording(s) could not be deleted",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun restore(id: Int) = viewModelScope.launch {
        recordingDao.getById(id)?.let {
            repository.restoreRecording(it)
        }
    }

    fun permanentDelete(recording: Recording) = viewModelScope.launch {
        repository.permanentlyDeleteRecording(recording)
    }

    fun exportToOtg(recording: Recording, otgDir: File, onComplete: (Boolean) -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            try {
                val sourceFile = File(recording.path)
                if (!sourceFile.exists()) {
                    withContext(Dispatchers.Main) { onComplete(false) }
                    return@withContext
                }

                if (!otgDir.exists()) {
                    otgDir.mkdirs()
                }

                val destFile = File(otgDir, sourceFile.name)

                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                withContext(Dispatchers.Main) { onComplete(true) }

            } catch (e: Exception) {
                Log.e("RecordingViewModel", "Failed to export to OTG", e)
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }
}
