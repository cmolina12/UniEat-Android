package co.edu.uniandes.unieat.data.pilot

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import co.edu.uniandes.unieat.data.analytics.EventKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/*
 * Samuel — BQ-13 (Type 5 = Type 1 + Type 3), from Sprint 1 §6.5:
 *
 *   "In a controlled pilot, how do feed-rendering time and the share of sessions ending without a menu
 *    selection differ between cache-enabled and online-only variants under comparable connection conditions?"
 *
 * This file holds the client side of the pilot:
 *   - PilotVariant: the two groups and the deterministic assignment.
 *   - PilotEnrollment: voluntary opt-in stored in DataStore (preferences).
 *   - CachePilot: records one row per visit in Room, serialized by a Mutex.
 * The feed asks [FeedPilot] which group the user is in and reports what happened; the Profile section
 * uses [PilotMembership] to join or leave. Both are interfaces so the ViewModels can be tested with fakes.
 */

/** The two arms of the pilot. [wireName] is what Room and the API store. */
enum class PilotVariant(val wireName: String) {
    /** Cache then network: the saved feed appears at once and the network replaces it. The normal app. */
    CACHE_ENABLED("cache_enabled"),

    /** Network only: the feed waits for the server and never shows the saved copy, not even offline. */
    ONLINE_ONLY("online_only");

    companion object {
        /** Changing it starts a new, independent assignment (a new pilot). */
        private const val SALT = "bq13-v1:"

        /**
         * Deterministic 50/50 split: the lowest bit of the first byte of MD5("bq13-v1:" + userId).
         *
         * - Deterministic: the same account lands in the same group on any phone and after reinstalling.
         *   If leaving and re-joining re-rolled the group, a student unhappy with "online only" could keep
         *   re-joining until getting the cache, and the two groups would stop being comparable.
         * - Random-looking: MD5 mixes every character, so the split does not follow anything about the
         *   user (sign-up order, email). MD5 is used as a hash, not for security.
         * - Checkable: PostgreSQL computes the same group with
         *   `get_byte(decode(md5('bq13-v1:' || user_id), 'hex'), 0) & 1`, so the server can reject a
         *   session that claims the other group.
         */
        fun assign(userId: String): PilotVariant {
            val digest = MessageDigest.getInstance("MD5").digest((SALT + userId).toByteArray(Charsets.UTF_8))
            return if ((digest[0].toInt() and 1) == 0) CACHE_ENABLED else ONLINE_ONLY
        }

        fun fromWire(value: String): PilotVariant? = entries.firstOrNull { it.wireName == value }
    }
}

/** Where the first menus on screen came from. */
enum class ContentSource(val wireName: String) { CACHE("cache"), NETWORK("network") }

/** What the feed tells the pilot. Implemented by [CachePilot]; faked in the FeedViewModel tests. */
interface FeedPilot {
    /** The current user's group, or null when they did not join: the feed then behaves as always (cache on). */
    suspend fun variant(): PilotVariant?

    /** A feed load began. [token] ties the later calls to this load; [cacheAgeMs] null = no eligible copy. */
    suspend fun onLoadStarted(token: String, variant: PilotVariant, startedAtMillis: Long, cacheAgeMs: Long?)

    /** The screen drew menus for the load [token]. */
    suspend fun onContentDrawn(token: String, source: ContentSource, atMillis: Long)

    /** The request of load [token] failed; [showedSavedCopy] tells whether the copy stayed on screen. */
    suspend fun onLoadFailed(token: String, showedSavedCopy: Boolean)
}

/** What the student sees in Perfil. */
data class PilotStatus(val signedIn: Boolean, val joined: Boolean, val variant: PilotVariant?)

/** Join and leave from Perfil. Implemented by [CachePilot]. */
interface PilotMembership {
    suspend fun status(): PilotStatus

    /** false when there is no signed-in user to enroll. */
    suspend fun join(): Boolean

    suspend fun leave()
}

/** Who joined the pilot. Only the user id is kept: the group is recomputed from it ([PilotVariant.assign]). */
interface PilotEnrollment {
    suspend fun joinedUser(): String?

    suspend fun join(userId: String)

    suspend fun leave()
}

/**
 * Local storage, preferences kind: one key in the shared DataStore file "unieat_settings" (the same file
 * Camilo uses for the Rendimiento period and the saved profile, never a second DataStore on that file).
 * A key-value pair is the right size for a yes/no consent; a table would be overkill.
 */
class DataStorePilotEnrollment(private val store: DataStore<Preferences>) : PilotEnrollment {
    override suspend fun joinedUser(): String? =
        // An unreadable file must not break the feed: it reads as "not enrolled", the safe default.
        store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }.first()[JOINED_USER]

    override suspend fun join(userId: String) {
        store.edit { it[JOINED_USER] = userId }
    }

    override suspend fun leave() {
        store.edit { it.remove(JOINED_USER) }
    }

    private companion object {
        val JOINED_USER = stringPreferencesKey("cache_pilot_user_id")
    }
}

/**
 * Records the pilot visits.
 *
 * Multithreading: the feed (Main), the event tracker (IO, app scope) and the upload worker (WorkManager
 * thread) all touch the same "current visit". Every public method takes [mutex] first, so the
 * read-modify-write of that row is atomic: two callers can never both read the old row and the second
 * write erase the first one's change. kotlinx Mutex is fair (FIFO), so calls are applied in the order they
 * were made, e.g. "load started" is always applied before "content drawn". Disk work runs on [io].
 *
 * The visit kept in [current] is also written to Room on every change (write-through), so a visit
 * survives the process dying and is closed and uploaded on the next launch.
 */
class CachePilot(
    private val enrollment: PilotEnrollment,
    private val dao: CachePilotDao,
    /** Signed-in user id (or the demo user); null when nobody is signed in. */
    private val currentUser: () -> String?,
    private val connectionType: () -> String,
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    /** A visit ended while the app is running: asks WorkManager to upload it (eventual connectivity). */
    private val onVisitEnded: () -> Unit = {},
) : FeedPilot, PilotMembership {
    private val mutex = Mutex()

    /** The open visit of this process, if any. Only read or written while holding [mutex]. */
    private var current: CachePilotSession? = null

    /** Token of the load that opened [current]; draws and failures of any other load are ignored. */
    private var firstLoadToken: String? = null

    // ---------------------------------------------------------------- membership (Perfil)

    override suspend fun status(): PilotStatus {
        val user = currentUser() ?: return PilotStatus(signedIn = false, joined = false, variant = null)
        val joined = enrollment.joinedUser() == user
        return PilotStatus(signedIn = true, joined = joined, variant = if (joined) PilotVariant.assign(user) else null)
    }

    override suspend fun join(): Boolean {
        val user = currentUser() ?: return false
        enrollment.join(user)
        return true
    }

    /** Leaving stops the recording at once and deletes what was not sent yet. Sent visits stay on the server. */
    override suspend fun leave() {
        enrollment.leave()
        mutex.withLock {
            withContext(io) { dao.deleteNotUploaded() }
            current = null
            firstLoadToken = null
        }
    }

    /** Sign-out: everything about the pilot belongs to the account that is leaving. */
    suspend fun clear() {
        enrollment.leave()
        mutex.withLock {
            withContext(io) { dao.clear() }
            current = null
            firstLoadToken = null
        }
    }

    // ---------------------------------------------------------------- recording (feed and events)

    override suspend fun variant(): PilotVariant? {
        val user = currentUser() ?: return null
        return if (enrollment.joinedUser() == user) PilotVariant.assign(user) else null
    }

    override suspend fun onLoadStarted(token: String, variant: PilotVariant, startedAtMillis: Long, cacheAgeMs: Long?) {
        mutex.withLock {
            val open = current
            if (open != null && open.variant == variant.wireName && startedAtMillis - open.lastActivityMillis < IDLE_MILLIS) {
                // Same visit (a reload, a filter change, a shake): it stays alive, its first load keeps the metrics.
                save(open.copy(lastActivityMillis = startedAtMillis))
                return@withLock
            }
            // Idle for 30 minutes, or the group changed: the old visit ends and this load opens a new one.
            if (open != null) {
                save(open.ended())
                onVisitEnded()
            }
            firstLoadToken = token
            save(
                CachePilotSession(
                    sessionId = newSessionId(),
                    variant = variant.wireName,
                    startedAtMillis = startedAtMillis,
                    lastActivityMillis = startedAtMillis,
                    connectionType = connectionType(),
                    cacheAvailable = cacheAgeMs != null,
                    cacheAgeMs = cacheAgeMs,
                ),
            )
        }
    }

    override suspend fun onContentDrawn(token: String, source: ContentSource, atMillis: Long) {
        mutex.withLock {
            val open = current ?: return@withLock
            if (token != firstLoadToken || open.closed) return@withLock
            val elapsed = (atMillis - open.startedAtMillis).coerceAtLeast(0)
            var next = open.copy(lastActivityMillis = maxOf(open.lastActivityMillis, atMillis))
            if (next.firstContentMs == null) {
                next = next.copy(firstContentMs = elapsed, firstContentSource = source.wireName)
            }
            if (source == ContentSource.NETWORK && next.networkRenderMs == null) {
                next = next.copy(networkRenderMs = elapsed, outcome = OUTCOME_NETWORK)
            }
            save(next)
        }
    }

    override suspend fun onLoadFailed(token: String, showedSavedCopy: Boolean) {
        mutex.withLock {
            val open = current ?: return@withLock
            if (token != firstLoadToken || open.outcome != OUTCOME_PENDING) return@withLock
            save(open.copy(outcome = if (showedSavedCopy) OUTCOME_CACHE_ONLY else OUTCOME_FAILURE))
        }
    }

    /**
     * Every analytics event (impression, detail open, selection, arrival, maps) is activity of the visit;
     * a [EventKind.SELECTION] also marks it as "ended with a selection". Called by PilotEventTracker.
     * An event after 30 idle minutes does not belong to that visit any more and is ignored.
     */
    suspend fun onInteraction(kind: EventKind, atMillis: Long) {
        mutex.withLock {
            val open = current ?: return@withLock
            if (open.closed || atMillis - open.lastActivityMillis >= IDLE_MILLIS) return@withLock
            save(
                open.copy(
                    lastActivityMillis = maxOf(open.lastActivityMillis, atMillis),
                    selected = open.selected || kind == EventKind.SELECTION,
                ),
            )
        }
    }

    // ---------------------------------------------------------------- upload support

    /**
     * Ends every open visit that is not this process's live one: rows left open by a previous launch, and
     * the live one too once it has been idle for 30 minutes. Called by the upload worker before sending.
     */
    suspend fun closeFinishedSessions() {
        val now = clock().toEpochMilli()
        mutex.withLock {
            withContext(io) {
                dao.deleteUploadedOlderThan(now - KEEP_DAYS * DAY_MILLIS)
                dao.open().forEach { row ->
                    val live = row.sessionId == current?.sessionId
                    if (!live || now - row.lastActivityMillis >= IDLE_MILLIS) {
                        dao.upsert(row.ended())
                        if (live) {
                            current = null
                            firstLoadToken = null
                        }
                    }
                }
            }
        }
    }

    suspend fun pendingUpload(limit: Int): List<CachePilotSession> = withContext(io) { dao.pendingUpload(limit) }

    suspend fun markUploaded(sessions: List<CachePilotSession>) {
        if (sessions.isEmpty()) return
        withContext(io) { dao.markUploaded(sessions.map { it.sessionId }) }
    }

    /** This phone's own BQ-13 answer: used in demo mode and when the server summary cannot be reached. */
    suspend fun report(days: Int): CachePilotReport = withContext(io) {
        val now = clock().toEpochMilli()
        CachePilotReport.build(dao.since(now - days * DAY_MILLIS), days)
    }

    /** Writes the row to Room (on IO) and keeps it as the current visit. Caller holds [mutex]. */
    private suspend fun save(session: CachePilotSession) {
        withContext(io) { dao.upsert(session) }
        current = if (session.closed) null else session
    }

    companion object {
        /** 30 minutes without feed activity or events ends a visit (a lunch decision takes far less). */
        const val IDLE_MILLIS = 30L * 60 * 1000
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        /** Sent rows are kept 29 days so the local fallback can still answer the 28-day period. */
        private const val KEEP_DAYS = 29L

        const val OUTCOME_PENDING = "pending"
        const val OUTCOME_NETWORK = "network"
        const val OUTCOME_CACHE_ONLY = "cache_only"
        const val OUTCOME_FAILURE = "failure"
        const val OUTCOME_ABANDONED = "abandoned"
    }
}
