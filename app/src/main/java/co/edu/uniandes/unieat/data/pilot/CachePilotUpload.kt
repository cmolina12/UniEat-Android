package co.edu.uniandes.unieat.data.pilot

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

/** Body of POST /telemetry/cache-pilot. */
@Serializable
data class CachePilotBatch(
    val sessions: List<CachePilotSessionDto>,
    val platform: String = "android",
)

@Serializable
data class CachePilotBatchResponse(
    val accepted: Int = 0,
    val duplicates: Int = 0,
)

/**
 * Samuel — BQ-13 endpoints behind the Repository pattern: the ViewModels and the worker never see HTTP,
 * and demo mode swaps in [LocalCachePilotRepository] without touching them.
 */
interface CachePilotRepository {
    suspend fun upload(sessions: List<CachePilotSessionDto>)

    /** The BQ-13 answer for the last [days] days. */
    suspend fun summary(days: Int): CachePilotReport
}

/** Demo mode and fallback: nothing leaves the phone, and the answer covers this device only. */
class LocalCachePilotRepository(private val pilot: CachePilot) : CachePilotRepository {
    override suspend fun upload(sessions: List<CachePilotSessionDto>) = Unit

    override suspend fun summary(days: Int): CachePilotReport = pilot.report(days)
}

/**
 * Shared backend. Eventual connectivity for the dashboard card: "network falling back to local data".
 * If the summary cannot be fetched (no signal, server error, endpoint not deployed yet) the card still
 * answers with this device's visits and says so, instead of an empty error.
 */
class RemoteCachePilotRepository(
    private val api: ApiClient,
    private val local: CachePilotRepository,
) : CachePilotRepository {
    override suspend fun upload(sessions: List<CachePilotSessionDto>) {
        api.send<CachePilotBatchResponse, CachePilotBatch>(HttpMethod.Post, "telemetry/cache-pilot", CachePilotBatch(sessions))
    }

    override suspend fun summary(days: Int): CachePilotReport = try {
        api.get<CachePilotReport>("telemetry/cache-pilot/summary", mapOf("days" to days))
    } catch (_: ApiException) {
        local.summary(days)
    }
}

/**
 * Sends ended visits in batches of at most 100. Same rules as the BQ-01 uploader:
 *  - nothing is deleted until the server accepted it (eventual connectivity: "queue and retry");
 *  - the server ignores a repeated sessionId, so a batch resent after a timeout is never counted twice;
 *  - a batch the server calls invalid would fail forever, so it is marked as sent and skipped.
 */
class CachePilotUploader(
    private val pilot: CachePilot,
    private val repository: CachePilotRepository,
    private val batchSize: Int = 100,
) {
    suspend fun flush(): FlushResult {
        // Visits left open by a previous launch (or idle for 30 min) end now, so they can be sent.
        pilot.closeFinishedSessions()
        while (true) {
            val batch = pilot.pendingUpload(batchSize)
            if (batch.isEmpty()) return FlushResult.DONE
            try {
                repository.upload(batch.map { it.toDto() })
                pilot.markUploaded(batch)
            } catch (e: ApiException) {
                when (e.code) {
                    ApiException.VALIDATION_ERROR -> pilot.markUploaded(batch)
                    // OFFLINE, AUTH_REQUIRED, RATE_LIMITED, NOT_FOUND (endpoint not deployed), 5xx: keep for later.
                    else -> return FlushResult.RETRY_LATER
                }
            }
        }
    }
}

/**
 * Multithreading: the upload runs in a WorkManager CoroutineWorker, off the main thread and outside any
 * screen, so it survives the user leaving the app and even a reboot.
 */
class CachePilotUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uploader = (applicationContext as UniEatApplication).container.cachePilotUploader
        return when (uploader.flush()) {
            FlushResult.DONE -> Result.success()
            FlushResult.RETRY_LATER -> Result.retry()
        }
    }
}

/**
 * Eventual connectivity: the job only starts with a connected network and retries with exponential
 * backoff (30 s, 60 s, 120 s…). KEEP: if an upload is already waiting, it will also take the new visits.
 */
class WorkManagerCachePilotScheduler(private val context: Context) : FlushScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<CachePilotUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UNIQUE_NAME = "unieat-cache-pilot-flush"
    }
}
