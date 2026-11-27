package co.edu.uniandes.unieat.data.myreports

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import co.edu.uniandes.unieat.core.model.MyReport
import co.edu.uniandes.unieat.core.model.ReportedPlace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Camilo, Sprint 4. Copy of Mis reportes on the phone, so the view opens without signal.
 * Two tables because a place has many reports: the list reads only the places, a place reads its reports
 * by `establishmentId` (indexed). One account at a time, everything is deleted on sign-out.
 */
@Entity(tableName = "reported_places")
data class ReportedPlaceRow(
    @PrimaryKey val establishmentId: String,
    val name: String,
    val area: String,
    val total: Int,
    val pending: Int,
    val confirmed: Int,
    val dismissed: Int,
    val observations: Int,
    val lastReportAtMillis: Long,
    /** when this copy arrived from the server, shown as "guardado a las HH:MM" */
    val savedAtMillis: Long,
) {
    fun toModel() = ReportedPlace(
        establishmentId, name, area, null, total, pending, confirmed, dismissed, observations,
        Instant.ofEpochMilli(lastReportAtMillis),
    )

    companion object {
        fun from(place: ReportedPlace, savedAt: Instant) = ReportedPlaceRow(
            place.establishmentId, place.name, place.area, place.total, place.pending, place.confirmed,
            place.dismissed, place.observations, place.lastReportAt.toEpochMilli(), savedAt.toEpochMilli(),
        )
    }
}

@Entity(tableName = "my_reports", indices = [Index("establishmentId")])
data class MyReportRow(
    @PrimaryKey val id: String,
    val establishmentId: String,
    val kind: String,
    val publicationId: String,
    val version: Int,
    val menuTitle: String,
    val note: String,
    val observedWaitMinutes: Int?,
    val status: String,
    val resolutionSource: String?,
    val observedAtMillis: Long,
    val resolvedAtMillis: Long?,
    val savedAtMillis: Long,
) {
    fun toModel() = MyReport(
        id, kind, publicationId, version, menuTitle, note, observedWaitMinutes, status, resolutionSource,
        Instant.ofEpochMilli(observedAtMillis), resolvedAtMillis?.let(Instant::ofEpochMilli),
    )

    companion object {
        fun from(establishmentId: String, report: MyReport, savedAt: Instant) = MyReportRow(
            report.id, establishmentId, report.kind, report.publicationId, report.version, report.menuTitle,
            report.note, report.observedWaitMinutes, report.status, report.resolutionSource,
            report.observedAt.toEpochMilli(), report.resolvedAt?.toEpochMilli(), savedAt.toEpochMilli(),
        )

        /**
         * Version 3 -> 4. Only creates the two tables, so the reports waiting for signal, Samuel's pilot visits
         * and saved menus stay. The SQL is what Room generates for the entities; Room checks it on open.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reported_places` (`establishmentId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `area` TEXT NOT NULL, `total` INTEGER NOT NULL, " +
                        "`pending` INTEGER NOT NULL, `confirmed` INTEGER NOT NULL, `dismissed` INTEGER NOT NULL, " +
                        "`observations` INTEGER NOT NULL, `lastReportAtMillis` INTEGER NOT NULL, " +
                        "`savedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`establishmentId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `my_reports` (`id` TEXT NOT NULL, `establishmentId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `publicationId` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                        "`menuTitle` TEXT NOT NULL, `note` TEXT NOT NULL, `observedWaitMinutes` INTEGER, " +
                        "`status` TEXT NOT NULL, `resolutionSource` TEXT, `observedAtMillis` INTEGER NOT NULL, " +
                        "`resolvedAtMillis` INTEGER, `savedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_my_reports_establishmentId` ON `my_reports` (`establishmentId`)")
            }
        }
    }
}

@Dao
interface ReportHistoryDao {
    @Query("SELECT * FROM reported_places ORDER BY lastReportAtMillis DESC")
    suspend fun places(): List<ReportedPlaceRow>

    @Query("SELECT * FROM reported_places WHERE establishmentId = :establishmentId")
    suspend fun place(establishmentId: String): ReportedPlaceRow?

    @Query("SELECT * FROM my_reports WHERE establishmentId = :establishmentId ORDER BY observedAtMillis DESC")
    suspend fun reports(establishmentId: String): List<MyReportRow>

    @Insert
    suspend fun insertPlaces(rows: List<ReportedPlaceRow>)

    @Insert
    suspend fun insertReports(rows: List<MyReportRow>)

    @Query("DELETE FROM reported_places")
    suspend fun deletePlaces()

    @Query("DELETE FROM my_reports WHERE establishmentId = :establishmentId")
    suspend fun deleteReportsOf(establishmentId: String)

    @Query("DELETE FROM my_reports WHERE establishmentId NOT IN (SELECT establishmentId FROM reported_places)")
    suspend fun deleteOrphanReports()

    @Query("DELETE FROM my_reports")
    suspend fun deleteReports()

    /** The list replaces the old one in one transaction: a reader never sees half a list. */
    @Transaction
    suspend fun replacePlaces(rows: List<ReportedPlaceRow>) {
        deletePlaces()
        insertPlaces(rows)
        // a place that left the list (older than the 100 newest reports) takes its reports with it
        deleteOrphanReports()
    }

    @Transaction
    suspend fun replaceReports(establishmentId: String, rows: List<MyReportRow>) {
        deleteReportsOf(establishmentId)
        insertReports(rows)
    }

    @Transaction
    suspend fun clear() {
        deleteReports()
        deletePlaces()
    }
}

/** What the phone has, and when it arrived. */
data class SavedPlaces(val places: List<ReportedPlace>, val savedAt: Instant)

data class SavedPlace(val place: ReportedPlace, val reports: List<MyReport>, val savedAt: Instant)

/** Reads and writes on IO: Room must never run on Main. */
class ReportHistoryStore(
    private val dao: ReportHistoryDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun savePlaces(places: List<ReportedPlace>) = withContext(io) {
        val now = clock()
        dao.replacePlaces(places.map { ReportedPlaceRow.from(it, now) })
    }

    suspend fun loadPlaces(): SavedPlaces? = withContext(io) {
        val rows = dao.places()
        // empty table = never loaded with signal, not "no reports"
        if (rows.isEmpty()) null else SavedPlaces(rows.map { it.toModel() }, Instant.ofEpochMilli(rows.minOf { it.savedAtMillis }))
    }

    suspend fun savePlace(place: ReportedPlace, reports: List<MyReport>) = withContext(io) {
        val now = clock()
        dao.replaceReports(place.establishmentId, reports.map { MyReportRow.from(place.establishmentId, it, now) })
    }

    suspend fun loadPlace(establishmentId: String): SavedPlace? = withContext(io) {
        val place = dao.place(establishmentId) ?: return@withContext null
        val rows = dao.reports(establishmentId)
        if (rows.isEmpty()) null
        else SavedPlace(place.toModel(), rows.map { it.toModel() }, Instant.ofEpochMilli(rows.minOf { it.savedAtMillis }))
    }

    suspend fun clear() = withContext(io) { dao.clear() }
}
