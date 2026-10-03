package co.edu.uniandes.unieat.data.telemetry

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
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.remote.ApiException
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

/** Body of POST /telemetry/feed-loads. */
@Serializable
data class FeedLoadBatch(
    val loads: List<FeedLoadRecord>,
    val platform: String = "android",
)

@Serializable
data class FeedLoadBatchResponse(
    val accepted: Int = 0,
    val duplicates: Int = 0,
)

/** BQ-01 endpoints behind the Repository pattern. */
interface FeedLoadRepository {
    suspend fun upload(loads: List<FeedLoadRecord>)

    /** The BQ-01 answer for the last [days] days. */
    suspend fun summary(days: Int): FeedLoadReport
}

/** Demo mode and fallback: nothing leaves the phone and the answer covers this device only. */
class LocalFeedLoadRepository(private val telemetry: FeedLoadTelemetry) : FeedLoadRepository {
    override suspend fun upload(loads: List<FeedLoadRecord>) = Unit

    override suspend fun summary(days: Int): FeedLoadReport = telemetry.report(days)
}

/** Shared backend. If the summary cannot be fetched, the card still shows this device's records. */
class RemoteFeedLoadRepository(
    private val api: ApiClient,
    private val local: FeedLoadRepository,
) : FeedLoadRepository {
    override suspend fun upload(loads: List<FeedLoadRecord>) {
        api.send<FeedLoadBatchResponse, FeedLoadBatch>(HttpMethod.Post, "telemetry/feed-loads", FeedLoadBatch(loads))
    }

    override suspend fun summary(days: Int): FeedLoadReport = try {
        api.get<FeedLoadReport>("telemetry/feed-loads/summary", mapOf("days" to days))
    } catch (_: ApiException) {
        local.summary(days)
    }
}

/**
 * Sends settled BQ-01 records in batches of at most 100. The server ignores repeated load ids,
 * so a batch resent after a timeout is never counted twice.
 */
class FeedLoadUploader(
    private val outbox: FeedLoadOutbox,
    private val repository: FeedLoadRepository,
    private val batchSize: Int = 100,
) {
    suspend fun flush(): FlushResult {
        while (true) {
            val batch = outbox.pendingUpload(batchSize)
            if (batch.isEmpty()) return FlushResult.DONE
            try {
                repository.upload(batch)
                outbox.markUploaded(batch)
            } catch (e: ApiException) {
                when (e.code) {
                    // A malformed batch would fail forever and block the newer records.
                    ApiException.VALIDATION_ERROR -> outbox.markUploaded(batch)
                    // OFFLINE, AUTH_REQUIRED (signed out), RATE_LIMITED, 5xx: keep them for later.
                    else -> return FlushResult.RETRY_LATER
                }
            }
        }
    }
}

class FeedLoadUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uploader = (applicationContext as UniEatApplication).container.feedLoadUploader
        return when (uploader.flush()) {
            FlushResult.DONE -> Result.success()
            FlushResult.RETRY_LATER -> Result.retry()
        }
    }
}

/** Same policy as the event queue: runs with network, survives restarts, backs off on errors. */
class WorkManagerFeedLoadScheduler(private val context: Context) : FlushScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<FeedLoadUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UNIQUE_NAME = "unieat-feed-load-flush"
    }
}
