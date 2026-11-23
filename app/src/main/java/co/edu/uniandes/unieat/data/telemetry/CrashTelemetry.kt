package co.edu.uniandes.unieat.data.telemetry

import android.content.Context
import android.os.Build
import co.edu.uniandes.unieat.BuildConfig
import co.edu.uniandes.unieat.core.model.UniEatJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.util.UUID

/** One uncaught exception, with the last screen and action the app had recorded before it. */
@Serializable
data class CrashRecord(
    val crashId: String,
    val sessionId: String,
    val occurredAtEpochMs: Long,
    val screen: String,
    val precedingAction: String,
    val exceptionType: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val uploaded: Boolean = false,
)

/** One app session and the screens it visited: the denominator of "crashes per 1,000 sessions". */
@Serializable
data class SessionVisitRecord(
    val sessionId: String,
    val startedAtEpochMs: Long,
    val screens: List<String>,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val uploaded: Boolean = false,
)

@Serializable
data class CrashGroup(
    val screen: String,
    val precedingAction: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val crashes: Int,
    val sessionsVisitingScreen: Int,
    val crashesPer1000Sessions: Double,
)

/**
 * BQ-02 answer. The backend sends it from GET /telemetry/crashes/summary with [scope] = [SCOPE_ALL_DEVICES];
 * [CrashTelemetry.report] builds the same shape from this phone only.
 */
@Serializable
data class CrashReport(
    val periodDays: Int,
    val crashes: Int,
    val sessions: Int,
    val groups: List<CrashGroup> = emptyList(),
    val scope: String = SCOPE_ALL_DEVICES,
)

/** Records waiting to be sent. [CrashTelemetry] is the real one; tests use a fake. */
interface CrashOutbox {
    /** Crashes and ended sessions not uploaded yet, oldest first, at most [limit] of each. */
    fun pendingUpload(limit: Int): CrashBatch

    fun markUploaded(batch: CrashBatch)
}

/** Body of POST /telemetry/crashes. */
@Serializable
data class CrashBatch(
    val crashes: List<CrashRecord> = emptyList(),
    val sessions: List<SessionVisitRecord> = emptyList(),
    val platform: String = "android",
) {
    val isEmpty: Boolean get() = crashes.isEmpty() && sessions.isEmpty()
}

/**
 * Samuel — BQ-02. Remembers the screen and the action the student was on, counts the screens each session
 * visited, and writes a crash record when an exception reaches the top of a thread.
 *
 * Why plain blocking file I/O and not Room or coroutines: the crash handler runs on the thread that is
 * dying, with the process about to be killed. A synchronous write finishes before the process ends;
 * a coroutine launched from there would not. Every access holds [lock], so the handler and the upload
 * worker cannot overwrite each other's changes.
 *
 * Not recorded: stack traces and messages. They may contain data about the student; the exception class,
 * the screen and the device are enough to rank where the app fails.
 */
class CrashTelemetry(
    context: Context,
    private val clock: () -> Instant = Instant::now,
    private val sessionId: String = UUID.randomUUID().toString(),
    private val onCrash: () -> Unit = {},
) : CrashOutbox {
    private val appContext = context.applicationContext
    private val crashFile = File(appContext.filesDir, "analytics/crash-telemetry.json")
    private val sessionFile = File(appContext.filesDir, "analytics/session-visits.json")
    private val lock = Any()

    @Volatile private var screen: String = SCREEN_NONE

    @Volatile private var action: String = ACTION_START
    private val visited = linkedSetOf<String>()
    private val startedAt = clock().toEpochMilli()

    private var crashCache: List<CrashRecord>? = null
    private var sessionCache: List<SessionVisitRecord>? = null

    /** The student opened [route] (any navigation destination). */
    fun onScreen(route: String?) {
        val name = screenName(route) ?: return
        screen = name
        action = ACTION_OPEN
        synchronized(lock) {
            if (visited.add(name)) saveCurrentSession()
        }
    }

    /** Something the student did on the current screen (the name of a tracked analytics event). */
    fun onAction(name: String) {
        action = name
    }

    /** Called by the uncaught-exception handler. Never throws: a failure here must not hide the real crash. */
    fun recordCrash(error: Throwable) {
        try {
            val record = CrashRecord(
                crashId = UUID.randomUUID().toString(), sessionId = sessionId,
                occurredAtEpochMs = clock().toEpochMilli(), screen = screen, precedingAction = action,
                exceptionType = error.javaClass.name,
                deviceModel = Build.MODEL ?: "unknown",
                osVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                appVersion = BuildConfig.VERSION_NAME,
            )
            synchronized(lock) {
                val cutoff = clock().minusSeconds(RETENTION_DAYS * 86_400L).toEpochMilli()
                writeCrashes(readCrashes().filter { it.occurredAtEpochMs >= cutoff } + record)
            }
            onCrash()
        } catch (_: Throwable) {
            // nothing else to do
        }
    }

    /**
     * Installs the handler. The previous one (the system's, which shows "the app stopped") still runs
     * afterwards, so the behaviour the student sees does not change.
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            recordCrash(error)
            previous?.uncaughtException(thread, error)
        }
    }

    override fun pendingUpload(limit: Int): CrashBatch = synchronized(lock) {
        CrashBatch(
            crashes = readCrashes().filter { !it.uploaded }.take(limit),
            // The running session is still collecting screens: only ended sessions are sent.
            sessions = readSessions().filter { !it.uploaded && it.sessionId != sessionId }.take(limit),
        )
    }

    override fun markUploaded(batch: CrashBatch) {
        synchronized(lock) {
            val crashIds = batch.crashes.map { it.crashId }.toSet()
            writeCrashes(readCrashes().map { if (it.crashId in crashIds) it.copy(uploaded = true) else it })
            val sessionIds = batch.sessions.map { it.sessionId }.toSet()
            writeSessions(readSessions().map { if (it.sessionId in sessionIds) it.copy(uploaded = true) else it })
        }
    }

    suspend fun hasPending(): Boolean = withContext(Dispatchers.IO) { !pendingUpload(1).isEmpty }

    /** This phone's own answer: demo mode and fallback when the backend summary cannot be reached. */
    suspend fun report(days: Int = 7): CrashReport = withContext(Dispatchers.IO) {
        synchronized(lock) {
            buildReport(readCrashes(), readSessions(), days, clock()).copy(scope = SCOPE_THIS_DEVICE)
        }
    }

    private fun saveCurrentSession() {
        val current = SessionVisitRecord(
            sessionId = sessionId, startedAtEpochMs = startedAt, screens = visited.toList(),
            deviceModel = Build.MODEL ?: "unknown",
            osVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            appVersion = BuildConfig.VERSION_NAME,
        )
        val cutoff = clock().minusSeconds(RETENTION_DAYS * 86_400L).toEpochMilli()
        val others = readSessions().filter { it.sessionId != sessionId && it.startedAtEpochMs >= cutoff }
        writeSessions(others + current)
    }

    private fun readCrashes(): List<CrashRecord> = crashCache ?: readFile<CrashRecord>(crashFile).also { crashCache = it }

    private fun readSessions(): List<SessionVisitRecord> =
        sessionCache ?: readFile<SessionVisitRecord>(sessionFile).also { sessionCache = it }

    private inline fun <reified T> readFile(file: File): List<T> = try {
        if (!file.exists()) emptyList() else UniEatJson.decodeFromString<List<T>>(file.readText())
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeCrashes(records: List<CrashRecord>) {
        crashFile.parentFile?.mkdirs()
        crashFile.writeText(UniEatJson.encodeToString(records))
        crashCache = records
    }

    private fun writeSessions(records: List<SessionVisitRecord>) {
        sessionFile.parentFile?.mkdirs()
        sessionFile.writeText(UniEatJson.encodeToString(records))
        sessionCache = records
    }

    companion object {
        const val SCREEN_NONE = "none"
        const val ACTION_START = "app_start"
        const val ACTION_OPEN = "screen_open"
        internal const val RETENTION_DAYS = 8

        /** "co.edu...navigation.Detail/{id}" → "Detail". Null when there is no route. */
        internal fun screenName(route: String?): String? =
            route?.substringBefore('/')?.substringBefore('?')?.substringAfterLast('.')?.takeIf { it.isNotBlank() }

        /**
         * Crashes per 1,000 sessions that visited the crashed screen, for each combination of screen,
         * preceding action, device model, OS and app version. A group's sessions are those with the same
         * device, OS and app version that visited the screen, so the rate compares like with like.
         */
        internal fun buildReport(
            crashes: List<CrashRecord>,
            sessions: List<SessionVisitRecord>,
            days: Int,
            now: Instant,
        ): CrashReport {
            val cutoff = now.minusSeconds(days * 86_400L).toEpochMilli()
            val recentCrashes = crashes.filter { it.occurredAtEpochMs >= cutoff }
            val recentSessions = sessions.filter { it.startedAtEpochMs >= cutoff }
            val groups = recentCrashes
                .groupBy { listOf(it.screen, it.precedingAction, it.deviceModel, it.osVersion, it.appVersion) }
                .map { (key, values) ->
                    val visiting = recentSessions.count { s ->
                        key[0] in s.screens && s.deviceModel == key[2] && s.osVersion == key[3] && s.appVersion == key[4]
                    }
                    // A session that crashed may not have been saved yet: never fewer sessions than crashes.
                    val denominator = maxOf(visiting, values.size)
                    CrashGroup(
                        screen = key[0], precedingAction = key[1], deviceModel = key[2], osVersion = key[3],
                        appVersion = key[4], crashes = values.size, sessionsVisitingScreen = denominator,
                        crashesPer1000Sessions = values.size * 1000.0 / denominator,
                    )
                }
                .sortedWith(compareByDescending<CrashGroup> { it.crashesPer1000Sessions }.thenByDescending { it.crashes })
            return CrashReport(days, recentCrashes.size, recentSessions.size, groups)
        }
    }
}
