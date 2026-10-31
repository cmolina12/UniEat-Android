package co.edu.uniandes.unieat.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import co.edu.uniandes.unieat.core.model.ReportBody
import co.edu.uniandes.unieat.core.model.ReportKind
import kotlinx.coroutines.flow.Flow

/** A row of pending_reports: an offline report with what POST /reports needs, when it was made and failed attempts. */
@Entity(tableName = "pending_reports", indices = [Index("publicationId")])
data class PendingReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val publicationId: String,
    val version: Int,
    /** Room stores the enum name ("NOT_FOUND"), ReportBody turns it into "not_found". */
    val kind: ReportKind,
    val note: String?,
    val observedWaitMinutes: Int?,
    val createdAtMillis: Long,
    val attempts: Int = 0,
) {
    fun toBody() = ReportBody(publicationId, version, kind, note, observedWaitMinutes)

    companion object {
        fun from(body: ReportBody, createdAtMillis: Long) = PendingReport(
            publicationId = body.publicationId,
            version = body.version,
            kind = body.kind,
            note = body.note,
            observedWaitMinutes = body.observedWaitMinutes,
            createdAtMillis = createdAtMillis,
        )
    }
}

/** Queries for the queue. Room generates the code at compile time (KSP). */
@Dao
interface PendingReportDao {
    @Insert
    suspend fun insert(report: PendingReport): Long

    /** Oldest first so they arrive in order. */
    @Query("SELECT * FROM pending_reports ORDER BY createdAtMillis ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingReport>

    @Query("DELETE FROM pending_reports WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_reports SET attempts = attempts + 1 WHERE id = :id")
    suspend fun markFailedAttempt(id: Long)

    /** Too old to still describe the menu the student saw. */
    @Query("DELETE FROM pending_reports WHERE createdAtMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    /** Keeps the [keep] newest rows so the table can't grow forever. */
    @Query("DELETE FROM pending_reports WHERE id NOT IN (SELECT id FROM pending_reports ORDER BY createdAtMillis DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    /** Live count per menu for "N reporte(s) esperando señal" in the detail. */
    @Query("SELECT COUNT(*) FROM pending_reports WHERE publicationId = :publicationId")
    fun countFor(publicationId: String): Flow<Int>

    @Query("DELETE FROM pending_reports")
    suspend fun clear()
}
