package com.vega.sting.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vega.sting.MainActivity
import com.vega.sting.R
import com.vega.sting.core.RecordingStateManager
import com.vega.sting.settings.SettingsManager
import com.vega.sting.storage.StorageHealthManager
import com.vega.sting.storage.StorageManager
import com.vega.sting.storage.StoragePolicy
import com.vega.sting.storage.StorageStatus
import com.vega.sting.storage.StorageType
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class SupportReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settingsManager = SettingsManager(applicationContext)

        val remindersEnabled = settingsManager.supportRemindersEnabled.first()
        if (!remindersEnabled) {
            return Result.success()
        }

        if (RecordingStateManager.isRecording()) {
            SupportReminderScheduler.scheduleInHours(applicationContext, BACKOFF_HOURS)
            return Result.retry()
        }

        if (!hasNotificationPermission()) {
            SupportReminderScheduler.scheduleInHours(applicationContext, BACKOFF_HOURS)
            return Result.retry()
        }

        val storageType = readStorageType(settingsManager)
        val dir = StorageManager.getRecordingDirectory(applicationContext, storageType)
        val snapshot = StorageHealthManager.snapshot(dir)
        val settings = settingsManager.recordingSettings.first()
        val status = StoragePolicy.statusFor(
            snapshot.freeBytes,
            settings.lowStorageWarningBytes,
            settings.minimumFreeSpaceBytes,
            snapshot.available
        )
        if (status == StorageStatus.CRITICAL || status == StorageStatus.RESERVE_REACHED ||
            status == StorageStatus.STORAGE_ERROR || status == StorageStatus.STORAGE_UNAVAILABLE
        ) {
            SupportReminderScheduler.scheduleInHours(applicationContext, BACKOFF_HOURS)
            return Result.retry()
        }

        val now = System.currentTimeMillis()
        val nextScheduled = settingsManager.nextSupportReminderAt.first()
        if (nextScheduled > 0 && now < nextScheduled - SCHEDULE_GRACE_MS) {
            val delayMs = (nextScheduled - now).coerceAtLeast(TimeUnit.MINUTES.toMillis(1))
            SupportReminderScheduler.scheduleAt(applicationContext, delayMs)
            return Result.success()
        }

        showSupportNotification(applicationContext)

        val nextDelayMs = nextCooldownMs()
        val nextAt = now + nextDelayMs
        settingsManager.setLastSupportReminderAt(now)
        settingsManager.setNextSupportReminderAt(nextAt)
        SupportReminderScheduler.scheduleAt(applicationContext, nextDelayMs)

        return Result.success()
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
        }
    }

    private suspend fun readStorageType(settingsManager: SettingsManager): StorageType =
        when (settingsManager.storageLocation.first()) {
            "SD_CARD" -> StorageType.SD_CARD
            "OTG" -> StorageType.OTG
            else -> StorageType.INTERNAL
        }

    private fun showSupportNotification(context: Context) {
        createChannel(context)

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val supportUrl = "https://github.com/Kvijay199428/VEGA-STING"
        val supportIntent = Intent(Intent.ACTION_VIEW, Uri.parse(supportUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val supportPending = PendingIntent.getActivity(
            context,
            2001,
            supportIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, SUPPORT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Enjoying VEGA STING?")
            .setContentText("Please support us! Your encouragement helps us continue improving the app.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Please support us! Your encouragement helps us continue improving the app. Thank you for being part of our journey. ❤️"))
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification, "Support us", supportPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        NotificationManagerCompat.from(context).notify(SUPPORT_NOTIFICATION_ID, builder.build())
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SUPPORT_CHANNEL_ID,
                "Support Reminders",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Occasional support reminders"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val WORK_NAME = "support_reminder"
        private const val SUPPORT_CHANNEL_ID = "SupportReminderChannel"
        private const val SUPPORT_NOTIFICATION_ID = 2001
        private const val BACKOFF_HOURS = 6L
        private val SCHEDULE_GRACE_MS = TimeUnit.MINUTES.toMillis(30)

        private fun nextCooldownMs(): Long {
            val options = listOf(
                TimeUnit.DAYS.toMillis(5),
                TimeUnit.DAYS.toMillis(10),
                TimeUnit.DAYS.toMillis(15)
            )
            return options[Random.nextInt(options.size)]
        }
    }
}
