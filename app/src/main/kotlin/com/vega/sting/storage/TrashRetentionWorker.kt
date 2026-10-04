package com.vega.sting.storage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit


class TrashRetentionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        TrashRetentionManager.cleanupOldTrash(applicationContext)
        Result.success()
    } catch (e: Exception) {
        
        
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        private const val TAG = "TrashRetentionWorker"
        private const val WORK_NAME = "trash_retention_sweep"
        private const val MAX_ATTEMPTS = 3
        private const val INTERVAL_HOURS = 24L

        
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrashRetentionWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
            )
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
