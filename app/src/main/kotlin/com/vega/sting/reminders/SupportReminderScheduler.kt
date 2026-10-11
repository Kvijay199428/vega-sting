package com.vega.sting.reminders

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.vega.sting.settings.SettingsManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

object SupportReminderScheduler {
    private const val WORK_NAME = "support_reminder_once"

    fun scheduleInitial(context: Context, delayMs: Long) {
        scheduleAt(context, delayMs)
    }

    fun scheduleAt(context: Context, delayMs: Long) {
        val delay = if (delayMs < 0) 0L else delayMs
        val request = OneTimeWorkRequestBuilder<SupportReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun scheduleInHours(context: Context, hours: Long) {
        scheduleAt(context, TimeUnit.HOURS.toMillis(hours))
    }

    suspend fun ensureScheduled(context: Context, settingsManager: SettingsManager) {
        val enabled = settingsManager.supportRemindersEnabled.first()
        if (!enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            return
        }
        val next = settingsManager.nextSupportReminderAt.first()
        val now = System.currentTimeMillis()
        if (next <= 0) {
            val delay = TimeUnit.DAYS.toMillis(7)
            settingsManager.setNextSupportReminderAt(now + delay)
            scheduleAt(context, delay)
            return
        }
        if (next < now) {
            scheduleAt(context, 0)
            return
        }
        scheduleAt(context, next - now)
    }
}
