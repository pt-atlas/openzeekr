package com.openzeekr.app.auto

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Keeps the automation checks running in the background.
 *
 *  - A self-rescheduling one-time job ([AutomationWorker]) does the checks; each run picks its own
 *    next delay (3 min while driving, the parked interval otherwise, or sooner when a timer is due).
 *  - A 15-minute periodic watchdog restarts that chain if Android ever drops it.
 *
 * WorkManager survives app restarts and reboots. Android may still delay checks while the phone is
 * in deep sleep (Doze); exempting the app from battery optimisation reduces that.
 */
object AutomationScheduler {
    private const val CHAIN = "oz-automation-check"
    private const val WATCHDOG = "oz-automation-watchdog"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Start or stop background checks to match the current settings. Safe to call often. */
    fun sync(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (AutomationStore.get(ctx).config.value.anyEnabled) {
            wm.enqueueUniquePeriodicWork(
                WATCHDOG,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
            )
            ensureChain(ctx)
        } else {
            wm.cancelUniqueWork(CHAIN)
            wm.cancelUniqueWork(WATCHDOG)
        }
    }

    /** Run a check right away (replacing any pending one), e.g. after the settings change. */
    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            CHAIN,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AutomationWorker>().setConstraints(network).build(),
        )
    }

    /** Called by the worker at the end of a check. APPEND so it doesn't cancel the running check itself. */
    internal fun scheduleNext(ctx: Context, delayMs: Long) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            CHAIN,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<AutomationWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(network)
                .build(),
        )
    }

    /** Start the chain only if nothing is queued or running. */
    private fun ensureChain(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            CHAIN,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutomationWorker>().setConstraints(network).build(),
        )
    }

    internal suspend fun chainAlive(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(CHAIN).get()
                .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
        }.getOrDefault(false)
    }

    /** Restarts the check chain if it died. Does no car work itself. */
    class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext
            if (!AutomationStore.get(app).config.value.anyEnabled) return Result.success()
            if (!chainAlive(app)) runNow(app)
            return Result.success()
        }
    }
}
