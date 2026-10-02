package co.edu.uniandes.unieat.data.analytics

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
import java.util.concurrent.TimeUnit

/** Uploads the queue. WorkManager only starts it with network and retries it with backoff. */
class EventFlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uploader = (applicationContext as UniEatApplication).container.eventUploader
        return when (uploader.flush()) {
            FlushResult.DONE -> Result.success()
            FlushResult.RETRY_LATER -> Result.retry()
        }
    }
}

/**
 * "Retry when back online": the work waits for [NetworkType.CONNECTED], survives app restarts,
 * and backs off exponentially on server errors.
 */
class WorkManagerFlushScheduler(private val context: Context) : FlushScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<EventFlushWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP: a pending upload already sends everything in the queue, new events included.
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UNIQUE_NAME = "unieat-event-flush"
    }
}
