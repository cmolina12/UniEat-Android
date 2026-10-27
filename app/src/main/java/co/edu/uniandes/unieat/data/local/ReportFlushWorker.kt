package co.edu.uniandes.unieat.data.local

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.data.analytics.FlushResult
import co.edu.uniandes.unieat.data.analytics.FlushScheduler
import java.util.concurrent.TimeUnit

/** Runs [ReportQueue.flush] in the background; WorkManager only starts it once there is a connection. */
class ReportFlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val queue = (applicationContext as UniEatApplication).container.reportQueue
        return when (queue.flush()) {
            FlushResult.DONE -> Result.success()
            FlushResult.RETRY_LATER -> Result.retry()
        }
    }
}

/** Same pattern as the analytics events: one unique job, network required, exponential retries. */
class WorkManagerReportScheduler(private val context: Context) : FlushScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<ReportFlushWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP: if an upload is already waiting, it will also take the new report.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UNIQUE_NAME = "unieat-report-flush"
    }
}
