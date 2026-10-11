package com.vega.sting.reminders

import android.content.Context
import com.vega.sting.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object SupportReminderInitializer {
    fun initialize(context: Context, settingsManager: SettingsManager) {
        CoroutineScope(Dispatchers.IO).launch {
            val enabled = settingsManager.supportRemindersEnabled.first()
            if (!enabled) return@launch
            val next = settingsManager.nextSupportReminderAt.first()
            val now = System.currentTimeMillis()
            when {
                next <= 0 -> {
                    val delay = TimeUnit.DAYS.toMillis(7)
                    settingsManager.setNextSupportReminderAt(now + delay)
                    SupportReminderScheduler.scheduleAt(context, delay)
                }
                next < now -> {
                    SupportReminderScheduler.scheduleAt(context, 0)
                }
                else -> {
                    SupportReminderScheduler.scheduleAt(context, next - now)
                }
            }
        }
    }
}
