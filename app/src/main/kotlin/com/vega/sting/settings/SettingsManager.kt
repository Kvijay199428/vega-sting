package com.vega.sting.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.vega.sting.storage.StoragePolicy
import com.vega.sting.updater.UpdateChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    companion object {
        val VIDEO_CODEC = stringPreferencesKey("video_codec")
        val AUDIO_CODEC = stringPreferencesKey("audio_codec")
        val RESOLUTION = stringPreferencesKey("resolution")
        val RATIO = stringPreferencesKey("ratio")
        val FPS = intPreferencesKey("fps")
        val STABILIZATION_ENABLED = booleanPreferencesKey("stabilization_enabled")
        val WATERMARK_ENABLED = booleanPreferencesKey("watermark_enabled")
        val TIMESTAMP_ENABLED = booleanPreferencesKey("timestamp_enabled")
        val TIMESTAMP_POSITION = stringPreferencesKey("timestamp_position")
        val SIGNATURE_POSITION = stringPreferencesKey("signature_position")
        val TIMESTAMP_FORMAT = stringPreferencesKey("timestamp_format")
        val OVERLAY_TEXT_SIZE = intPreferencesKey("overlay_text_size")
        val SIGNATURE = stringPreferencesKey("signature")
        val POWER_BUTTON_MODE = stringPreferencesKey("power_button_mode")
        val STORAGE_LOCATION = stringPreferencesKey("storage_location")
        val ORIENTATION_MODE = stringPreferencesKey("orientation_mode")
        val SORT_TYPE = stringPreferencesKey("sort_type")
        val SORT_ORDER = stringPreferencesKey("sort_order")

        // UPDATE CHANNEL (runtime-selectable: STABLE / DEBUG)
        val SELECTED_UPDATE_CHANNEL = stringPreferencesKey("selected_update_channel")

        // FILE SIZE & STORAGE SAFETY
        val SPLIT_RECORDING_ENABLED = booleanPreferencesKey("split_recording_enabled")
        val VIDEO_SEGMENT_LIMIT_BYTES = longPreferencesKey("video_segment_limit_bytes")
        val AUDIO_SEGMENT_LIMIT_BYTES = longPreferencesKey("audio_segment_limit_bytes")
        val MINIMUM_FREE_SPACE_BYTES = longPreferencesKey("minimum_free_space_bytes")
        val LOW_STORAGE_WARNING_BYTES = longPreferencesKey("low_storage_warning_bytes")
        val AUDIO_FALLBACK_ENABLED = booleanPreferencesKey("audio_fallback_enabled")

        // SUPPORT REMINDERS
        val SUPPORT_REMINDERS_ENABLED = booleanPreferencesKey("support_reminders_enabled")
        val LAST_SUPPORT_REMINDER_AT = longPreferencesKey("last_support_reminder_at")
        val NEXT_SUPPORT_REMINDER_AT = longPreferencesKey("next_support_reminder_at")
    }

    val videoCodec: Flow<String> = context.dataStore.data.map { it[VIDEO_CODEC] ?: "H.265" }
    val audioCodec: Flow<String> = context.dataStore.data.map { it[AUDIO_CODEC] ?: "AAC" }
    val resolution: Flow<String> = context.dataStore.data.map { it[RESOLUTION] ?: "AUTO" }
    val videoRatio: Flow<String> = context.dataStore.data.map { it[RATIO] ?: "AUTO" }
    val fps: Flow<Int> = context.dataStore.data.map { it[FPS] ?: 30 }
    val stabilizationEnabled: Flow<Boolean> = context.dataStore.data.map { it[STABILIZATION_ENABLED] ?: true }
    val watermarkEnabled: Flow<Boolean> = context.dataStore.data.map { it[WATERMARK_ENABLED] ?: true }
    val timestampEnabled: Flow<Boolean> = context.dataStore.data.map { it[TIMESTAMP_ENABLED] ?: true }
    val timestampPosition: Flow<String> = context.dataStore.data.map { it[TIMESTAMP_POSITION] ?: "TOP_CENTER" }
    val signaturePosition: Flow<String> = context.dataStore.data.map { it[SIGNATURE_POSITION] ?: "BOTTOM_LEFT" }
    val timestampFormat: Flow<String> = context.dataStore.data.map { it[TIMESTAMP_FORMAT] ?: "yyyy-MM-dd HH:mm:ss" }
    val overlayTextSize: Flow<Int> = context.dataStore.data.map { it[OVERLAY_TEXT_SIZE] ?: 18 }
    val signature: Flow<String> = context.dataStore.data.map { it[SIGNATURE] ?: "VEGA STING" }
    val powerButtonMode: Flow<String> = context.dataStore.data.map { it[POWER_BUTTON_MODE] ?: "VIDEO" }
    val storageLocation: Flow<String> = context.dataStore.data.map { it[STORAGE_LOCATION] ?: "INTERNAL" }
    val orientationMode: Flow<String> = context.dataStore.data.map { it[ORIENTATION_MODE] ?: "FOLLOW_SENSOR" }
    val sortType: Flow<String> = context.dataStore.data.map { it[SORT_TYPE] ?: "DATE" }
    val sortOrder: Flow<String> = context.dataStore.data.map { it[SORT_ORDER] ?: "DESC" }

    /**
     * The user-selected OTA update channel. Falls back to the channel baked into
     * the installed APK ([UpdateChannel.installed]) until the user explicitly
     * switches; a saved preference is never overwritten on startup.
     */
    val selectedUpdateChannel: Flow<UpdateChannel> =
        context.dataStore.data.map { UpdateChannel.fromPreference(it[SELECTED_UPDATE_CHANNEL]) }

    val supportRemindersEnabled: Flow<Boolean> = context.dataStore.data.map { it[SUPPORT_REMINDERS_ENABLED] ?: true }
    val lastSupportReminderAt: Flow<Long> = context.dataStore.data.map { it[LAST_SUPPORT_REMINDER_AT] ?: 0L }
    val nextSupportReminderAt: Flow<Long> = context.dataStore.data.map { it[NEXT_SUPPORT_REMINDER_AT] ?: 0L }

    val splitRecordingEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[SPLIT_RECORDING_ENABLED] ?: true }
    val videoSegmentLimitBytes: Flow<Long> = context.dataStore.data.map {
        StoragePolicy.clampSegmentLimit(it[VIDEO_SEGMENT_LIMIT_BYTES] ?: RecordingSettings.DEFAULTS.videoSegmentLimitBytes)
    }
    val audioSegmentLimitBytes: Flow<Long> = context.dataStore.data.map {
        StoragePolicy.clampSegmentLimit(it[AUDIO_SEGMENT_LIMIT_BYTES] ?: RecordingSettings.DEFAULTS.audioSegmentLimitBytes)
    }
    val minimumFreeSpaceBytes: Flow<Long> = context.dataStore.data.map {
        StoragePolicy.clampReserve(it[MINIMUM_FREE_SPACE_BYTES] ?: RecordingSettings.DEFAULTS.minimumFreeSpaceBytes)
    }
    val lowStorageWarningBytes: Flow<Long> = context.dataStore.data.map {
        (it[LOW_STORAGE_WARNING_BYTES] ?: RecordingSettings.DEFAULTS.lowStorageWarningBytes)
            .coerceAtLeast(StoragePolicy.MIN_RESERVE_BYTES)
    }
    val audioFallbackEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[AUDIO_FALLBACK_ENABLED] ?: true }

    /** One consistent snapshot of every storage-safety preference. */
    val recordingSettings: Flow<RecordingSettings> = context.dataStore.data.map { prefs ->
        RecordingSettings.sanitize(
            RecordingSettings(
                splitRecordingEnabled = prefs[SPLIT_RECORDING_ENABLED] ?: true,
                videoSegmentLimitBytes = prefs[VIDEO_SEGMENT_LIMIT_BYTES]
                    ?: RecordingSettings.DEFAULTS.videoSegmentLimitBytes,
                audioSegmentLimitBytes = prefs[AUDIO_SEGMENT_LIMIT_BYTES]
                    ?: RecordingSettings.DEFAULTS.audioSegmentLimitBytes,
                minimumFreeSpaceBytes = prefs[MINIMUM_FREE_SPACE_BYTES]
                    ?: RecordingSettings.DEFAULTS.minimumFreeSpaceBytes,
                lowStorageWarningBytes = prefs[LOW_STORAGE_WARNING_BYTES]
                    ?: RecordingSettings.DEFAULTS.lowStorageWarningBytes,
                audioFallbackEnabled = prefs[AUDIO_FALLBACK_ENABLED] ?: true
            )
        )
    }

    suspend fun updateVideoCodec(codec: String) {
        context.dataStore.edit { it[VIDEO_CODEC] = codec }
    }

    suspend fun updateStabilization(enabled: Boolean) {
        context.dataStore.edit { it[STABILIZATION_ENABLED] = enabled }
    }
    
    suspend fun updateWatermark(enabled: Boolean) {
        context.dataStore.edit { it[WATERMARK_ENABLED] = enabled }
    }

    suspend fun updateTimestampEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TIMESTAMP_ENABLED] = enabled }
    }

    suspend fun updateTimestampPosition(position: String) {
        context.dataStore.edit { it[TIMESTAMP_POSITION] = position }
    }

    suspend fun updateSignaturePosition(position: String) {
        context.dataStore.edit { it[SIGNATURE_POSITION] = position }
    }

    suspend fun updateSignature(text: String) {
        context.dataStore.edit { it[SIGNATURE] = text }
    }


    suspend fun updateTimestampFormat(format: String) {
        context.dataStore.edit { it[TIMESTAMP_FORMAT] = format }
    }

    suspend fun updateOverlayTextSize(size: Int) {
        context.dataStore.edit { it[OVERLAY_TEXT_SIZE] = size }
    }

    suspend fun updatePowerButtonMode(mode: String) {
        context.dataStore.edit { it[POWER_BUTTON_MODE] = mode }
    }

    suspend fun updateStorageLocation(location: String) {
        context.dataStore.edit { it[STORAGE_LOCATION] = location }
    }

    suspend fun updateResolution(resolution: String) {
        context.dataStore.edit { it[RESOLUTION] = resolution }
    }

    suspend fun updateVideoRatio(ratio: String) {
        context.dataStore.edit { it[RATIO] = ratio }
    }

    suspend fun updateOrientationMode(mode: String) {
        context.dataStore.edit { it[ORIENTATION_MODE] = mode }
    }

    suspend fun updateSort(type: String, order: String) {
        context.dataStore.edit {
            it[SORT_TYPE] = type
            it[SORT_ORDER] = order
        }
    }

    /** Persists the user's chosen OTA update channel. */
    suspend fun updateSelectedUpdateChannel(channel: UpdateChannel) {
        context.dataStore.edit { it[SELECTED_UPDATE_CHANNEL] = channel.name }
    }

    suspend fun updateSplitRecordingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SPLIT_RECORDING_ENABLED] = enabled }
    }

    suspend fun updateVideoSegmentLimitBytes(bytes: Long) {
        context.dataStore.edit { it[VIDEO_SEGMENT_LIMIT_BYTES] = StoragePolicy.clampSegmentLimit(bytes) }
    }

    suspend fun updateAudioSegmentLimitBytes(bytes: Long) {
        context.dataStore.edit { it[AUDIO_SEGMENT_LIMIT_BYTES] = StoragePolicy.clampSegmentLimit(bytes) }
    }

    suspend fun updateMinimumFreeSpaceBytes(bytes: Long) {
        context.dataStore.edit { it[MINIMUM_FREE_SPACE_BYTES] = StoragePolicy.clampReserve(bytes) }
    }

    suspend fun updateLowStorageWarningBytes(bytes: Long) {
        context.dataStore.edit {
            it[LOW_STORAGE_WARNING_BYTES] = bytes.coerceAtLeast(StoragePolicy.MIN_RESERVE_BYTES)
        }
    }

    suspend fun updateAudioFallbackEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUDIO_FALLBACK_ENABLED] = enabled }
    }

    suspend fun updateSupportRemindersEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SUPPORT_REMINDERS_ENABLED] = enabled }
    }

    suspend fun setLastSupportReminderAt(timeMillis: Long) {
        context.dataStore.edit { it[LAST_SUPPORT_REMINDER_AT] = timeMillis }
    }

    suspend fun setNextSupportReminderAt(timeMillis: Long) {
        context.dataStore.edit { it[NEXT_SUPPORT_REMINDER_AT] = timeMillis }
    }

    suspend fun clearSupportReminderSchedule() {
        context.dataStore.edit {
            it.remove(LAST_SUPPORT_REMINDER_AT)
            it.remove(NEXT_SUPPORT_REMINDER_AT)
        }
    }
}
