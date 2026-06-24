package com.portalpad.app.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Scheduled backup job.
 *
 * Why one-time-with-delay instead of periodic: WorkManager's
 * PeriodicWorkRequest fires at fixed N-day intervals from when it's first
 * scheduled, not at a specific time of day or day of week. To do "every
 * Monday at 3am" we use OneTimeWorkRequest + setInitialDelay calculated to
 * the next occurrence. The worker re-enqueues itself when done, so the chain
 * keeps running until the user disables it.
 *
 * Doze caveat: Android may delay the worker by hours on aggressive OEMs
 * (Samsung, Xiaomi) when the device is idle. For backups, eventual freshness
 * is acceptable — we still get nightly backups, just maybe a few hours late.
 */
class BackupWorker(
    private val ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val mgr = BackupManager(ctx)
        try {
            val filename = mgr.backupNow()
            if (filename != null) {
                Log.d(TAG, "Scheduled backup wrote $filename")
            } else {
                Log.w(TAG, "Scheduled backup returned null — likely folder permission revoked")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Scheduled backup threw", t)
        }

        // Re-enqueue for the next occurrence based on current prefs, regardless
        // of whether this run succeeded. Otherwise a transient failure stops the
        // chain forever.
        try {
            val prefs = PreferencesRepository(ctx)
            val snapshot = prefs.rawDataStore.data.first()
            val freq = snapshot[PreferencesRepository.Keys.BACKUP_FREQUENCY] ?: "off"
            if (freq == "scheduled") {
                val day = snapshot[PreferencesRepository.Keys.BACKUP_DAY] ?: 0
                val hour = snapshot[PreferencesRepository.Keys.BACKUP_HOUR] ?: 3
                schedule(ctx, day, hour)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not re-enqueue next backup", t)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "BackupWorker"
        const val WORK_NAME = "portalpad-scheduled-backup"

        /**
         * Schedule the next backup to fire at the given day-of-week + hour-of-day.
         * @param dayOfWeek 0 = every day, 1-7 = Mon-Sun (ISO)
         * @param hour 0-23 local time
         */
        fun schedule(context: Context, dayOfWeek: Int, hour: Int) {
            val wm = WorkManager.getInstance(context)
            val delayMs = computeDelayMs(dayOfWeek, hour)
            Log.d(TAG, "Scheduling backup in ${delayMs / 60000} min (dayOfWeek=$dayOfWeek hour=$hour)")
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Milliseconds until the next occurrence of (dayOfWeek, hour). If
         * dayOfWeek == 0, "every day". Always at least 60 seconds away so we
         * don't fire instantly if the user picks the current hour.
         */
        private fun computeDelayMs(dayOfWeek: Int, hour: Int): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (dayOfWeek != 0) {
                // ISO day-of-week is 1=Mon..7=Sun; Calendar.DAY_OF_WEEK is 1=Sun..7=Sat
                val targetCalDow = ((dayOfWeek % 7) + 1)  // Mon(1)→Cal 2 ... Sun(7)→Cal 1
                while (target.get(Calendar.DAY_OF_WEEK) != targetCalDow || target.timeInMillis <= now.timeInMillis + 60_000) {
                    target.add(Calendar.DAY_OF_YEAR, 1)
                }
            } else if (target.timeInMillis <= now.timeInMillis + 60_000) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }

            return target.timeInMillis - now.timeInMillis
        }
    }
}
