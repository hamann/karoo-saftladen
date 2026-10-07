package io.github.hamann.saftladen.report

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.hamann.saftladen.container
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Retries buffered uploads outside of the extension's lifetime.
 *
 * Deliberately declares no network constraint: Karoo can reach the internet over
 * Bluetooth via the companion app, which Android's connectivity manager does not report
 * as a connection, so a `CONNECTED` constraint would keep this worker parked exactly when
 * delivery is possible. [ReportUploader] fails fast instead and we back off here.
 */
class ReportFlushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return when (val result = applicationContext.container.uploader.flush()) {
            is ReportUploader.Result.Done -> Result.success()
            is ReportUploader.Result.Retry -> {
                Timber.i("Flush will be retried: %s", result.message)
                Result.retry()
            }
        }
    }

    companion object {
        private const val UNIQUE_NOW = "saftladen-flush"
        private const val UNIQUE_PERIODIC = "saftladen-flush-periodic"

        /**
         * Try to deliver the buffer now, replacing any pending retry.
         *
         * Replacing is intentional: a freshly captured report should not have to sit out
         * the backoff of an earlier failure.
         */
        fun flushNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReportFlushWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.REPLACE, request)
        }

        /** Safety net so a buffer that survived a reboot still gets drained. */
        fun schedulePeriodicFlush(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReportFlushWorker>(1, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
