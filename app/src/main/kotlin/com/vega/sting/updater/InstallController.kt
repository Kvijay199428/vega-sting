package com.vega.sting.updater

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Launches the system Package Installer for a downloaded APK via FileProvider.
 *
 * Sideloaded apps (this one is signed with a local keystore, not distributed via
 * the Play Store) need the "Install unknown apps" capability before the system
 * Package Installer will accept an APK. On Android 8+ the caller must hold the
 * REQUEST_INSTALL_PACKAGES permission AND that permission must be runtime-enabled
 * for this package ("Allow from this source"). Without it the Package Installer
 * silently refuses the install. This controller checks the capability first and,
 * when missing, routes the user to the per-app settings screen before retrying.
 */
object InstallController {

    private const val TAG = "InstallController"

    /**
     * FileProvider authority, kept in sync with the manifest <provider>.
     * The manifest declares `${applicationId}.provider`.
     */
    private fun authority(context: Context): String = "${context.packageName}.provider"

    /** True if this app is allowed to request package installs ("Allow from this source"). */
    fun canInstallApks(context: Context): Boolean =
        try {
            context.packageManager.canRequestPackageInstalls()
        } catch (e: Exception) {
            // Very old devices may not expose the API; assume install is allowed.
            Log.w(TAG, "canRequestPackageInstalls unavailable, assuming allowed", e)
            true
        }

    /**
     * Starts the install flow for [apk]. Must be called from an Activity.
     *
     * If the app is not yet allowed to install apps, opens the system
     * "Install unknown apps" settings screen and tells the caller it was unable to
     * launch the install right now (the user should return and tap Update again).
     *
     * Use [requestInstallPermission] with an activity-result callback if you want a
     * seamless retry after the user enables the toggle.
     */
    fun startInstall(activity: Activity, apk: File) {
        try {
            if (!canInstallApks(activity)) {
                openUnknownSourcesSettings(activity)
                return
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                activity,
                authority(activity),
                apk
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            activity.startActivity(intent)
            Log.i(TAG, "Launched Package Installer for ${apk.name}")
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No activity to handle APK install", e)
            openUnknownSourcesSettings(activity)
        } catch (e: SecurityException) {
            Log.e(TAG, "Blocked from installing APK", e)
            openUnknownSourcesSettings(activity)
        } catch (e: IllegalArgumentException) {
            // Thrown by FileProvider when the file is not covered by file_paths.xml.
            Log.e(TAG, "APK path not covered by FileProvider config", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start install", e)
        }
    }

    /**
     * Opens the system "Install unknown apps" settings page for this package where
     * the user can enable "Allow from this source".
     */
    fun openUnknownSourcesSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            intent.data = Uri.parse("package:${activity.packageName}")
            activity.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:${activity.packageName}")
                activity.startActivity(intent)
            } catch (_: Exception) {
                Log.e(TAG, "Could not open install settings")
            }
        }
    }
}
