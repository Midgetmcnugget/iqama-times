package tv.projectivy.plugin.iqama

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Fetches new times in the background, separately from Projectivy.
 *
 * Projectivy only talks to the plugin for a moment when it wants a picture, and Android TV
 * often closes the plugin before the mosque's page has finished loading, so the times could
 * stay on yesterday's. A WorkManager job is allowed to run until it's done, survives restarts,
 * and runs every few hours plus just after midnight; when it gets new times it asks
 * Projectivy to redraw.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        Prefs.init(ctx)
        val force = inputData.getBoolean(KEY_FORCE, false)
        var ok = true
        if (force || MasjidboxFetcher.isStale(ignoreBackoff = true)) {
            val done = CountDownLatch(1)
            var result = false
            MasjidboxFetcher.refresh(ctx) { r -> result = r; done.countDown() }
            if (!done.await(3, TimeUnit.MINUTES)) Log.w(TAG, "Refresh took too long")
            ok = result
            Log.i(TAG, "Background refresh: ${if (ok) "new times" else "failed (${Prefs.lastError})"}")
        }
        // Redraw either way: the highlighted prayer and the day may have moved on.
        try { WallpaperProviderService.notifyProjectivy(ctx) } catch (e: Throwable) { Log.w(TAG, "Notify failed", e) }
        // The midnight job queues the next one to follow it (replacing itself would cancel this run).
        scheduleAfterMidnight(ctx, if (MIDNIGHT in tags) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE)
        // A failed fetch is retried sooner by WorkManager (with growing gaps).
        return if (ok || runAttemptCount >= 4) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "IqamaRefresh"
        private const val PERIODIC = "iqama-periodic-refresh"
        private const val MIDNIGHT = "iqama-midnight-refresh"
        private const val NOW = "iqama-refresh-now"
        private const val KEY_FORCE = "force"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Sets up the regular refreshes. Safe to call often: existing jobs are kept. */
        fun schedule(context: Context) {
            try {
                val wm = WorkManager.getInstance(context.applicationContext)
                wm.enqueueUniquePeriodicWork(
                    PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<RefreshWorker>(3, TimeUnit.HOURS)
                        .setConstraints(online)
                        .build()
                )
                scheduleAfterMidnight(context, ExistingWorkPolicy.KEEP)
            } catch (e: Throwable) {
                Log.w(TAG, "Couldn't schedule refreshes", e)
            }
        }

        /** A refresh a few minutes after midnight, for the new day's times. */
        private fun scheduleAfterMidnight(context: Context, policy: ExistingWorkPolicy) {
            val now = Calendar.getInstance()
            val next = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 5)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                MIDNIGHT, policy,
                OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                    .setConstraints(online)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                    .addTag(MIDNIGHT)
                    .build()
            )
        }

        /** Fetch as soon as there's a connection, e.g. when Projectivy finds the times out of date. */
        fun runSoon(context: Context) {
            try {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    NOW, ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(online)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build()
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Couldn't start a refresh", e)
            }
        }
    }
}
