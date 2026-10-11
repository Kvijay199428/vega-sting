package com.vega.sting

import android.app.Application
import com.vega.sting.reminders.SupportReminderInitializer
import com.vega.sting.settings.SettingsManager
import com.vega.sting.storage.TrashRetentionWorker

class StingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TrashRetentionWorker.schedule(this)
        try {
            SupportReminderInitializer.initialize(this, SettingsManager(this))
        } catch (_: Exception) {
        }
    }
}