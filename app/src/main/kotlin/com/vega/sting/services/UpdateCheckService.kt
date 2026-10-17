package com.vega.sting.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.vega.sting.core.RecordingStateManager
import com.vega.sting.settings.SettingsManager
import com.vega.sting.updater.UpdateChecker
import com.vega.sting.updater.UpdaterManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Carries an update found by [UpdateCheckService] to the UI.
 *
 * The result is held as a consumable slot rather than a replayed event so that
 * an automatic check never re-presents its dialog after a configuration change:
 * whoever shows the prompt clears the slot first.
 */
object UpdateCheckBus {
    private val slot = MutableStateFlow<UpdateChecker.UpdateInfo?>(null)

    val pending = slot

    fun publish(update: UpdateChecker.UpdateInfo) {
        slot.value = update
    }

    fun consume() {
        slot.value = null
    }
}

/**
 * Runs one non-blocking automatic update check and publishes the result on
 * [UpdateCheckBus]. Shared by [UpdateCheckService] (the normal path) and the
 * in-process fallback used when the platform refuses to start the service.
 *
 * All the update policy — the 6-hour throttle, the "skip while recording" guard
 * and the ignored-version list — lives in [UpdaterManager], so this adds no new
 * rules of its own.
 */
internal object UpdateCheckRunner {
    private const val TAG = "UpdateCheck"
    private const val CALLBACK_TIMEOUT_MS = 60_000L

    suspend fun run(context: Context) {
        val app = context.applicationContext
        val settingsManager = SettingsManager(app)
        val updater = UpdaterManager(app)

        val channel = settingsManager.selectedUpdateChannel.first()
        val finished = CompletableDeferred<Unit>()
        val started = updater.checkForUpdates(
            channel = channel,
            manual = false,
            isRecording = { RecordingStateManager.isBusy() }
        ) { result ->
            if (result is UpdaterManager.CheckResult.UpdateAvailable) {
                UpdateCheckBus.publish(result.update)
            }
            finished.complete(Unit)
        }

        if (!started) {
            // Suppressed by the recording guard or the throttle window: nothing
            // to show, and nothing to wait for.
            Log.i(TAG, "Automatic update check suppressed (recording in progress or throttled)")
            return
        }

        // The callback is always delivered on the main thread by UpdaterManager.
        // The timeout is only a safety net so the service can never linger.
        withTimeoutOrNull(CALLBACK_TIMEOUT_MS) { finished.await() }
    }
}

/**
 * Checks for a newer release in the background so the launch path is never
 * blocked by a modal progress dialog.
 *
 * A plain started service: it is only ever started while the app is in the
 * foreground (at launch, or when a recording finishes), so no foreground-service
 * notification is involved. The service stops itself as soon as the check has
 * reported back.
 */
class UpdateCheckService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Automatic update check service started")
        scope.launch {
            try {
                UpdateCheckRunner.run(this@UpdateCheckService)
            } catch (e: Exception) {
                // A check is best-effort: a failure must never take the service down.
                Log.w(TAG, "Automatic update check failed", e)
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "UpdateCheckService"

        /**
         * Starts a background check. If the platform refuses to start the
         * service (background-start restrictions on API 31+) the same check is
         * run in-process instead, so a launch can never silently lose its
         * update prompt.
         */
        fun startCheck(context: Context) {
            val app = context.applicationContext
            try {
                app.startService(Intent(app, UpdateCheckService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "startService refused, running the check in-process", e)
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    UpdateCheckRunner.run(app)
                }
            }
        }
    }
}
