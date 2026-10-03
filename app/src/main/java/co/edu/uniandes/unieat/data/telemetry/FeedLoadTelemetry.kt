package co.edu.uniandes.unieat.data.telemetry

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import co.edu.uniandes.unieat.BuildConfig
import co.edu.uniandes.unieat.core.model.UniEatJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.ceil

@Serializable
data class FeedLoadRecord(
    val loadId: String,
    val sessionId: String,
    val startedAtEpochMs: Long,
    val requestCompletedAtEpochMs: Long? = null,
    val renderedAtEpochMs: Long? = null,
    val outcome: String,
    val errorCode: String? = null,
    val connectionType: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    /** True once the backend has the record (POST /telemetry/feed-loads); it is kept for the local report. */
    val uploaded: Boolean = false,
)

data class FeedLoadAttempt(val loadId: String, val startedAtEpochMs: Long)

@Serializable
data class FeedLoadGroup(
    val connectionType: String,
    val deviceModel: String,
    val osVersion: String,
    val hour: Int,
    val attempts: Int,
    val failures: Int,
    val abandoned: Int,
    val failureRate: Double,
    val p95RequestToRenderMs: Long? = null,
)

/**
 * BQ-01 answer. The backend sends it from GET /telemetry/feed-loads/summary with
 * [scope] = [SCOPE_ALL_DEVICES]; [FeedLoadTelemetry.report] builds the same shape from this phone only.
 */
@Serializable
data class FeedLoadReport(
    val periodDays: Int,
    val attempts: Int,
    val abandoned: Int,
    val groups: List<FeedLoadGroup> = emptyList(),
    val scope: String = SCOPE_ALL_DEVICES,
)

const val SCOPE_ALL_DEVICES = "all_devices"
const val SCOPE_THIS_DEVICE = "this_device"

/** Records waiting to be sent to the backend. [FeedLoadTelemetry] is the real one; tests use a fake. */
interface FeedLoadOutbox {
    /** Settled records (rendered, failure or abandoned) not uploaded yet, oldest first. */
    suspend fun pendingUpload(limit: Int): List<FeedLoadRecord>

    /** Marks them as sent, keeping the settled outcome so a late render cannot change it. */
    suspend fun markUploaded(records: List<FeedLoadRecord>)
}

/**
 * BQ-01 records on the phone. They survive restarts, feed the local report and wait here until
 * [FeedLoadUploader] sends them to the backend. [onSettled] asks for that upload.
 */
class FeedLoadTelemetry(
    context: Context,
    private val clock: () -> Instant = Instant::now,
    private val sessionId: String = UUID.randomUUID().toString(),
    private val onSettled: () -> Unit = {},
) : FeedLoadOutbox {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "analytics/feed-load-telemetry.json")
    private val mutex = Mutex()

    suspend fun start(): FeedLoadAttempt = FeedLoadAttempt(UUID.randomUUID().toString(), clock().toEpochMilli())

    suspend fun success(attempt: FeedLoadAttempt) = append(
        base(attempt, outcome = "render_pending", requestCompleted = clock().toEpochMilli())
    )

    suspend fun failure(attempt: FeedLoadAttempt, errorCode: String) {
        append(base(attempt, outcome = "failure", requestCompleted = clock().toEpochMilli(), errorCode = errorCode))
        onSettled()
    }

    suspend fun rendered(loadId: String) {
        val updated = withContext(Dispatchers.IO) {
            mutex.withLock {
                val records = readUnsafe().toMutableList()
                val i = records.indexOfLast { it.loadId == loadId }
                if (i >= 0 && records[i].outcome == "render_pending") {
                    records[i] = records[i].copy(outcome = "rendered", renderedAtEpochMs = clock().toEpochMilli())
                    writeUnsafe(records)
                    true
                } else {
                    false
                }
            }
        }
        if (updated) onSettled()
    }

    override suspend fun pendingUpload(limit: Int): List<FeedLoadRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val nowMs = clock().toEpochMilli()
            readUnsafe().asSequence()
                .filter { !it.uploaded }
                .mapNotNull { settle(it, nowMs) }
                .take(limit)
                .toList()
        }
    }

    override suspend fun markUploaded(records: List<FeedLoadRecord>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sent = records.associateBy { it.loadId }
                val updated = readUnsafe().map { record ->
                    val settled = sent[record.loadId] ?: return@map record
                    // Keeps "abandoned" on disk: a render arriving later must not contradict the backend.
                    record.copy(outcome = settled.outcome, errorCode = settled.errorCode, uploaded = true)
                }
                writeUnsafe(updated)
            }
        }
    }

    /** This phone's own answer: used in demo mode and when the backend summary is not reachable. */
    suspend fun report(days: Int = 7): FeedLoadReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            buildReport(readUnsafe(), days, clock(), ZoneId.systemDefault()).copy(scope = SCOPE_THIS_DEVICE)
        }
    }

    private fun base(a: FeedLoadAttempt, outcome: String, requestCompleted: Long, errorCode: String? = null) = FeedLoadRecord(
        loadId = a.loadId, sessionId = sessionId, startedAtEpochMs = a.startedAtEpochMs,
        requestCompletedAtEpochMs = requestCompleted, outcome = outcome, errorCode = errorCode,
        connectionType = connectionType(), deviceModel = Build.MODEL ?: "unknown",
        osVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})", appVersion = BuildConfig.VERSION_NAME,
    )

    private suspend fun append(record: FeedLoadRecord) = withContext(Dispatchers.IO) { mutex.withLock {
        val records = readUnsafe().filter { it.startedAtEpochMs >= clock().minusSeconds(8 * 86_400L).toEpochMilli() }.toMutableList()
        records += record
        writeUnsafe(records)
    } }

    private fun readUnsafe(): List<FeedLoadRecord> = try {
        if (!file.exists()) emptyList() else UniEatJson.decodeFromString<List<FeedLoadRecord>>(file.readText())
    } catch (_: Exception) { emptyList() }

    private fun writeUnsafe(records: List<FeedLoadRecord>) {
        file.parentFile?.mkdirs(); file.writeText(UniEatJson.encodeToString(records))
    }

    private fun connectionType(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    companion object {
        internal const val ABANDONED_AFTER_MS = 60_000L

        /**
         * The outcome to report for [record] at [nowMs], or null while it can still change:
         * a load waiting for its render less than [ABANDONED_AFTER_MS] is not settled yet.
         */
        internal fun settle(record: FeedLoadRecord, nowMs: Long): FeedLoadRecord? = when {
            record.outcome != "render_pending" -> record
            nowMs - record.startedAtEpochMs >= ABANDONED_AFTER_MS ->
                record.copy(outcome = "abandoned", errorCode = "RENDER_ABANDONED")
            else -> null
        }

        /**
         * Builds BQ-01 locally. Abandoned renders are reported separately and excluded from
         * the technical-failure denominator: leaving the screen is not evidence of a network/render failure.
         */
        internal fun buildReport(
            source: List<FeedLoadRecord>,
            days: Int,
            now: Instant,
            zoneId: ZoneId,
        ): FeedLoadReport {
            val cutoff = now.minusSeconds(days * 86_400L).toEpochMilli()
            val nowMs = now.toEpochMilli()
            val records = source.filter { it.startedAtEpochMs >= cutoff }.map { record ->
                if (record.outcome == "render_pending" && nowMs - record.startedAtEpochMs >= ABANDONED_AFTER_MS) {
                    record.copy(outcome = "abandoned", errorCode = "RENDER_ABANDONED")
                } else record
            }
            val terminal = records.filter { it.outcome == "rendered" || it.outcome == "failure" }
            val abandoned = records.count { it.outcome == "abandoned" }
            val groups = records.groupBy {
                val hour = Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zoneId).hour
                listOf(it.connectionType, it.deviceModel, it.osVersion, hour.toString())
            }.mapNotNull { (key, values) ->
                val completed = values.filter { it.outcome == "rendered" || it.outcome == "failure" }
                val abandonedInGroup = values.count { it.outcome == "abandoned" }
                if (completed.isEmpty() && abandonedInGroup == 0) return@mapNotNull null
                val durations = completed.mapNotNull { r ->
                    r.renderedAtEpochMs?.let { it - r.startedAtEpochMs }
                }.sorted()
                val failures = completed.count { it.outcome == "failure" }
                FeedLoadGroup(
                    connectionType = key[0],
                    deviceModel = key[1],
                    osVersion = key[2],
                    hour = key[3].toInt(),
                    attempts = completed.size,
                    failures = failures,
                    abandoned = abandonedInGroup,
                    failureRate = if (completed.isEmpty()) 0.0 else failures.toDouble() / completed.size,
                    p95RequestToRenderMs = percentile95(durations),
                )
            }.sortedWith(
                compareByDescending<FeedLoadGroup> { it.failureRate }
                    .thenByDescending { it.p95RequestToRenderMs ?: -1 },
            )
            return FeedLoadReport(days, terminal.size, abandoned, groups)
        }

        internal fun percentile95(sorted: List<Long>): Long? {
            if (sorted.isEmpty()) return null
            val index = (ceil(sorted.size * .95).toInt() - 1).coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
    }
}
