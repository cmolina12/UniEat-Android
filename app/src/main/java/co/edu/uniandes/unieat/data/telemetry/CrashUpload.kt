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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

@Serializable
data class CrashBatchResponse(
    val accepted: Int = 0,
    val duplicates: Int = 0,
)

/** BQ-02 endpoints behind the Repository pattern, like BQ-01 and BQ-13. */
interface CrashRepository {
    suspend fun upload(batch: CrashBatch)

    /** The BQ-02 answer for the last [days] days. */
    suspend fun summary(days: Int): CrashReport
}

/** Demo mode and fallback: nothing leaves the phone and the answer covers this device only. */
class LocalCrashRepository(private val telemetry: CrashTelemetry) : CrashRepository {
    override suspend fun upload(batch: CrashBatch) = Unit

    override suspend fun summary(days: Int): CrashReport = telemetry.report(days)
}

/** Shared backend. If the summary cannot be fetched, the card still shows this device's crashes. */
class RemoteCrashRepository(
    private val api: ApiClient,
    private val local: CrashRepository,
) : CrashRepository {
    override suspend fun upload(batch: CrashBatch) {
        api.send<CrashBatchResponse, CrashBatch>(HttpMethod.Post, "telemetry/crashes", batch)
    }

    override suspend fun summary(days: Int): CrashReport = try {
        api.get<CrashReport>("telemetry/crashes/summary", mapOf("days" to days))
    } catch (_: ApiException) {
        local.summary(days)
    }
}

/**
 * Sends crashes and ended sessions in batches of at most 100 of each. Same rules as the BQ-01 uploader:
 * nothing is marked as sent until the server accepted it, and the server ignores repeated ids, so a batch
 * resent after a timeout is never counted twice.
 */
class CrashUploader(
    private val outbox: CrashOutbox,
    private val repository: CrashRepository,
    private val batchSize: Int = 100,
) {
    suspend fun flush(): FlushResult {
        while (true) {
            // Disk reads: off the caller's thread.
            val batch = withContext(Dispatchers.IO) { outbox.pendingUpload(batchSize) }
            if (batch.isEmpty) return FlushResult.DONE
            try {
                repository.upload(batch)
                withContext(Dispatchers.IO) { outbox.markUploaded(batch) }
            } catch (e: ApiException) {
                when (e.code) {
                    // A malformed batch would fail forever and block the newer records.
                    ApiException.VALIDATION_ERROR -> withContext(Dispatchers.IO) { outbox.markUploaded(batch) }
                    // OFFLINE, AUTH_REQUIRED, RATE_LIMITED, NOT_FOUND (endpoint not deployed), 5xx: keep for later.
                    else -> return FlushResult.RETRY_LATER
                }
            }
        }
    }
}

class CrashUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uploader = (applicationContext as UniEatApplication).container.crashUploader
        return when (uploader.flush()) {
            FlushResult.DONE -> Result.success()
            FlushResult.RETRY_LATER -> Result.retry()
        }
    }
}

/** Runs with network, survives restarts and backs off on errors (30 s, 60 s, 120 s…). */
class WorkManagerCrashScheduler(private val context: Context) : FlushScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<CrashUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UNIQUE_NAME = "unieat-crash-flush"
    }
}
