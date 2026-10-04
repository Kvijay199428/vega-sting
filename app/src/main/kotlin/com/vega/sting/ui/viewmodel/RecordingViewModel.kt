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
    companion object { private const val TAG = "RecordingViewModel" }

    private val recordingDao: RecordingDao = AppDatabase.getDatabase(application).recordingDao()
    private val repository = RecordingRepository(application, recordingDao)
    val allRecordings: Flow<List<Recording>> = recordingDao.getAllActiveRecordings()
    val trashRecordings: Flow<List<Recording>> = recordingDao.getTrashRecordings()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            reconcileMissingActiveRows()
            com.vega.sting.storage.TrashRetentionManager.cleanupOldTrash(application)
        }
        com.vega.sting.storage.TrashRetentionWorker.schedule(application)
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
