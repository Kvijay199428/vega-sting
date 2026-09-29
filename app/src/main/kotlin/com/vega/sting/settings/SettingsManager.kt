package com.vega.sting.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
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
}
