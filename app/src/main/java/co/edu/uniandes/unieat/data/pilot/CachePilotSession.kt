package co.edu.uniandes.unieat.data.pilot

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import co.edu.uniandes.unieat.core.model.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Samuel — BQ-13. One row of `cache_pilot_sessions`: one *visit* of a student who joined the cache pilot.
 *
 * A visit starts with the first feed load ("Hoy") and ends after [CachePilot.IDLE_MILLIS] without activity,
 * when the app is opened again (a new process), when the pilot group changes, or when the student leaves
 * the pilot. Only ended visits ([closed] = true) are uploaded and counted, because "ended without a
 * selection" can only be decided once the visit is over.
 *
 * The row describes the visit's **first** feed load, which is when a saved copy matters most: the
 * student opened the app and is waiting to see menus. Durations are stored in milliseconds relative
 * to [startedAtMillis] so the phone clock cannot shift them.
 *
 * Why Room (relational) and not a JSON file like BQ-01: these rows are *updated in place* many times
 * (draw, failure, selection, close, upload) and queried by state ("closed and not uploaded", "started
 * after X"). A table with a primary key does that with one UPDATE/SELECT instead of rewriting a whole
 * file, and it shares the database (and its migration path) that already holds the report queue.
 */
@Entity(tableName = "cache_pilot_sessions")
data class CachePilotSession(
    /** Random UUID of the visit; the server ignores a repeated id, so a resent batch is never counted twice. */
    @PrimaryKey val sessionId: String,
    /** [PilotVariant.wireName] of the group the student was in during this visit. */
    val variant: String,
    val startedAtMillis: Long,
    /** Last feed load, render or interaction; used to close a visit after 30 minutes of silence. */
    val lastActivityMillis: Long,
    /** [co.edu.uniandes.unieat.data.network.ConnectionType] at the first load: the "comparable conditions". */
    val connectionType: String,
    /** Whether an eligible saved copy existed, in BOTH groups (online-only has one too, it just does not use it). */
    val cacheAvailable: Boolean,
    /** Age of that copy when the visit started; null when there was none. */
    val cacheAgeMs: Long? = null,
    /** Load start → first menus on screen, from the copy or from the network. null = nothing was ever shown. */
    val firstContentMs: Long? = null,
    /** "cache" or "network": where those first menus came from. */
    val firstContentSource: String? = null,
    /** Load start → fresh menus from the server on screen. null = the request failed or the student left first. */
    val networkRenderMs: Long? = null,
    /** Technical outcome of the first load: pending, network, cache_only, failure, abandoned. */
    val outcome: String = CachePilot.OUTCOME_PENDING,
    /** Whether the student tapped "Elegir este menú" during the visit (the interaction outcome). */
    val selected: Boolean = false,
    val closed: Boolean = false,
    val uploaded: Boolean = false,
) {
    /** The visit is over: a load that never settled counts as abandoned, like BQ-01. */
    fun ended(): CachePilotSession = copy(
        closed = true,
        outcome = if (outcome == CachePilot.OUTCOME_PENDING) CachePilot.OUTCOME_ABANDONED else outcome,
    )

    fun toDto() = CachePilotSessionDto(
        sessionId = sessionId,
        variant = variant,
        connectionType = connectionType,
        cacheAvailable = cacheAvailable,
        cacheAgeMs = cacheAgeMs,
        firstContentMs = firstContentMs,
        firstContentSource = firstContentSource,
        networkRenderMs = networkRenderMs,
        outcome = outcome,
        selected = selected,
        startedAt = Instant.ofEpochMilli(startedAtMillis),
    )

    companion object {
        /**
         * Database version 1 → 2. Version 1 only had `pending_reports`; this adds the BQ-13 table and keeps
         * every report still waiting for a connection (a destructive migration would have lost them).
         * The SQL is exactly what Room generates for [CachePilotSession]; Room checks the result when the
         * database opens and fails loudly if a column, type or NOT NULL does not match.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cache_pilot_sessions` (" +
                        "`sessionId` TEXT NOT NULL, " +
                        "`variant` TEXT NOT NULL, " +
                        "`startedAtMillis` INTEGER NOT NULL, " +
                        "`lastActivityMillis` INTEGER NOT NULL, " +
                        "`connectionType` TEXT NOT NULL, " +
                        "`cacheAvailable` INTEGER NOT NULL, " +
                        "`cacheAgeMs` INTEGER, " +
                        "`firstContentMs` INTEGER, " +
                        "`firstContentSource` TEXT, " +
                        "`networkRenderMs` INTEGER, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`selected` INTEGER NOT NULL, " +
                        "`closed` INTEGER NOT NULL, " +
                        "`uploaded` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sessionId`))",
                )
            }
        }
    }
}

/** The SQL the pilot needs. Room generates the implementation at compile time (KSP), like PendingReportDao. */
@Dao
interface CachePilotDao {
    /** Insert or overwrite by primary key: every change to a visit is one write of the whole row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: CachePilotSession)

    @Query("SELECT * FROM cache_pilot_sessions WHERE closed = 0")
    suspend fun open(): List<CachePilotSession>

    /** Ended visits not sent yet, oldest first. */
    @Query("SELECT * FROM cache_pilot_sessions WHERE closed = 1 AND uploaded = 0 ORDER BY startedAtMillis ASC LIMIT :limit")
    suspend fun pendingUpload(limit: Int): List<CachePilotSession>

    @Query("UPDATE cache_pilot_sessions SET uploaded = 1 WHERE sessionId IN (:ids)")
    suspend fun markUploaded(ids: List<String>)

    /** Rows for the local answer (this device only), sent or not. */
    @Query("SELECT * FROM cache_pilot_sessions WHERE startedAtMillis >= :sinceMillis")
    suspend fun since(sinceMillis: Long): List<CachePilotSession>

    /** The dashboard looks back 28 days at most; older rows are only kept until they are sent. */
    @Query("DELETE FROM cache_pilot_sessions WHERE uploaded = 1 AND startedAtMillis < :cutoffMillis")
    suspend fun deleteUploadedOlderThan(cutoffMillis: Long)

    /** Leaving the pilot: what was not sent yet is deleted (consent withdrawn). */
    @Query("DELETE FROM cache_pilot_sessions WHERE uploaded = 0")
    suspend fun deleteNotUploaded()

    @Query("DELETE FROM cache_pilot_sessions")
    suspend fun clear()
}

/** One visit as POST /telemetry/cache-pilot receives it. Only technical and yes/no data: no location, no menu. */
@Serializable
data class CachePilotSessionDto(
    val sessionId: String,
    val variant: String,
    val connectionType: String,
    val cacheAvailable: Boolean,
    val cacheAgeMs: Long? = null,
    val firstContentMs: Long? = null,
    val firstContentSource: String? = null,
    val networkRenderMs: Long? = null,
    val outcome: String,
    val selected: Boolean,
    @Serializable(InstantSerializer::class) val startedAt: Instant,
)
