package uk.iqama.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Re-reads the mosque page in the background (a few times a day) and redraws the widget. */
class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val force = inputData.getBoolean(KEY_FORCE, false)
        // Refresh every mosque that's on a widget (one at a time).
        for (slug in IqamaWidget.mosquesInUse(applicationContext)) {
            if (force || Fetcher.isStale(prefs.mosque(slug))) {
                Fetcher.fetchBlocking(applicationContext, slug)
            }
        }
        IqamaWidget.updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val KEY_FORCE = "force"
        private const val PERIODIC = "iqama-refresh-periodic"
        private const val ONCE = "iqama-refresh-once"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Keeps a refresh running every few hours while a widget exists. */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequest.Builder(RefreshWorker::class.java, 3, TimeUnit.HOURS)
                .setConstraints(online)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Refreshes soon (e.g. when the widget is added or the times are out of date). */
        fun runNow(context: Context, force: Boolean = false) {
            val req = OneTimeWorkRequest.Builder(RefreshWorker::class.java)
                .setConstraints(online)
                .setInputData(Data.Builder().putBoolean(KEY_FORCE, force).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}
