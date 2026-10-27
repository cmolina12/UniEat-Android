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

/**
 * One row of `pending_reports`: a community report the student sent without internet.
 * It keeps exactly what POST /reports needs, plus when it was made and how many times sending failed.
 */
@Entity(tableName = "pending_reports", indices = [Index("publicationId")])
data class PendingReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val publicationId: String,
    val version: Int,
    /** Room stores the enum by name ("NOT_FOUND"); the API name ("not_found") comes back through ReportBody. */
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

/** The SQL the queue needs. Room generates the implementation at compile time (KSP). */
@Dao
interface PendingReportDao {
    @Insert
    suspend fun insert(report: PendingReport): Long

    /** Oldest first, so reports reach the server in the order they were made. */
    @Query("SELECT * FROM pending_reports ORDER BY createdAtMillis ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingReport>

    @Query("DELETE FROM pending_reports WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_reports SET attempts = attempts + 1 WHERE id = :id")
    suspend fun markFailedAttempt(id: Long)

    /** Reports too old to still describe the menu the student saw. */
    @Query("DELETE FROM pending_reports WHERE createdAtMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    /** Keeps only the [keep] newest rows, so a phone offline for days cannot grow the table forever. */
    @Query("DELETE FROM pending_reports WHERE id NOT IN (SELECT id FROM pending_reports ORDER BY createdAtMillis DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    /** Live count for one menu: the detail screen shows "1 reporte esperando conexión" and updates by itself. */
    @Query("SELECT COUNT(*) FROM pending_reports WHERE publicationId = :publicationId")
    fun countFor(publicationId: String): Flow<Int>

    @Query("DELETE FROM pending_reports")
    suspend fun clear()
}
