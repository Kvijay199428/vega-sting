package com.vega.sting.camera

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manages reading, writing, and invalidation of [DeviceProfile] JSON files.
 *
 * Profiles are stored in the app's internal files directory:
 *   files/device_profiles/<sanitized_fingerprint>.json
 *
 * Profile invalidation triggers:
 *   - No profile file exists
 *   - profileVersion mismatch (app upgrade added new fields)
 *   - fingerprint mismatch (OEM OTA changed HAL behavior)
 */
class DeviceProfileManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceProfileMgr"
        private const val PROFILES_DIR = "device_profiles"
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    private var cachedProfile: DeviceProfile? = null

    /** Serializes concurrent profile scans (cold-start + service pre-scan). */
    private val scanMutex = Mutex()

    /**
     * Returns the current device profile.
     * Loads from disk cache if available. If the profile is stale or missing,
     * runs the full capability scan (blocking on CameraX extension detection
     * requires a coroutine caller — use [scanAndSaveProfile] for that path).
     *
     * For the first call, prefer [getOrScanProfile] from a coroutine.
     */
    fun getProfile(): DeviceProfile? {
        cachedProfile?.let { return it }

        val file = getProfileFile()
        if (!file.exists()) return null

        return try {
            val json = file.readText()
            val profile = gson.fromJson(json, DeviceProfile::class.java)

            // Validate fingerprint + version
            if (profile.fingerprint != Build.FINGERPRINT ||
                profile.profileVersion != DeviceProfile.CURRENT_PROFILE_VERSION
            ) {
                Log.w(TAG, "Profile stale: fingerprint or version mismatch, rescan needed")
                return null
            }

            cachedProfile = profile
            profile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load profile", e)
            null
        }
    }

    /**
     * Coroutine-safe: returns existing profile or runs a full scan.
     * Extension detection requires CameraX async init, hence suspend.
     */
    suspend fun getOrScanProfile(): DeviceProfile {
        getProfile()?.let { return it }
        return scanAndSaveProfile()
    }

    /**
     * Runs a complete capability scan and persists the result.
     * Serialized so concurrent callers share a single scan.
     */
    suspend fun scanAndSaveProfile(): DeviceProfile = scanMutex.withLock {
        // Someone may have finished scanning while we waited for the lock
        getProfile()?.let { return@withLock it }
        return@withLock performScan()
    }

    private suspend fun performScan(): DeviceProfile {
        return withContext(Dispatchers.Default) {
            Log.i(TAG, "Starting full device capability scan…")

            val cameras = CameraCapabilityScanner.scan(context)
            val encoders = EncoderCapabilityScanner.scan()
            val extensions = CameraXExtensionDetector.detect(context)
            val vendorTags = CameraCapabilityScanner.scanVendorTags(context)

            val profile = DeviceProfile(
                profileVersion = DeviceProfile.CURRENT_PROFILE_VERSION,
                fingerprint = Build.FINGERPRINT,
                device = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
                sdkVersion = Build.VERSION.SDK_INT,
                scannedAt = System.currentTimeMillis(),
                cameras = cameras,
                encoders = encoders,
                extensions = extensions,
                vendorTags = vendorTags
            )

            saveProfile(profile)
            cachedProfile = profile
            Log.i(TAG, "Profile saved for ${profile.device} (${profile.fingerprint})")
            profile
        }
    }

    /**
     * Force-invalidates the cached profile (e.g., after a settings reset).
     */
    fun invalidate() {
        cachedProfile = null
        val file = getProfileFile()
        if (file.exists()) file.delete()
    }

    // ── Internal I/O ────────────────────────────────────────────────────

    private fun saveProfile(profile: DeviceProfile) {
        val file = getProfileFile()
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(profile))
    }

    private fun getProfileFile(): File {
        val dir = File(context.filesDir, PROFILES_DIR)
        val safeName = Build.FINGERPRINT
            .replace("/", "_")
            .replace(":", "_")
            .take(128)  // filesystem name length safety
        return File(dir, "$safeName.json")
    }
}
