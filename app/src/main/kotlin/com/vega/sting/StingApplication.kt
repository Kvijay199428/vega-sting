package com.vega.sting

import android.app.Application
import com.vega.sting.storage.TrashRetentionWorker


class StingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TrashRetentionWorker.schedule(this)
    }
}