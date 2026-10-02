package co.edu.uniandes.unieat.data.fake

import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.MenuReport
import co.edu.uniandes.unieat.core.model.ReportKind
import java.time.Instant
import java.util.UUID

/**
 * In-memory "server" shared by the debug fakes, so a report sent from the sheet shows up in the
 * next GET /menus/:id (e.g. the BQ-05 location warning). Lives for the app process only.
 */
object FakeBackend {

    data class StoredReport(
        val id: String,
        val publicationId: String,
        val version: Int,
        val kind: ReportKind,
        val status: String,
        val createdAt: Instant,
    )

    private val reports = mutableListOf<StoredReport>()

    /** Discrepancies wait for moderation; the rest are observations (api-v1 catalog). */
    private val discrepancies = setOf(ReportKind.UNAVAILABLE, ReportKind.PRICE, ReportKind.LOCATION)

    /** Seed menus plus the reports submitted in this process, like menu_json + publicReportsForVersion. */
    @Synchronized
    fun menus(now: Instant): List<DailyMenu> = SeedMenus.all(now).map { menu ->
        val submitted = reports
            .filter { it.publicationId == menu.id && it.version == menu.version && it.status != "observation" }
            .map { MenuReport(it.id, it.kind, it.status, it.createdAt) }
        val all = (submitted + menu.reports).sortedByDescending { it.createdAt }
        menu.copy(reports = all, pendingReports = all.count { it.status == "pending" })
    }

    @Synchronized
    fun addReport(publicationId: String, version: Int, kind: ReportKind, now: Instant): StoredReport {
        val status = if (kind in discrepancies) "pending" else "observation"
        return StoredReport(UUID.randomUUID().toString(), publicationId, version, kind, status, now).also { reports += it }
    }

    /** Reports by the (single) fake student since [since], optionally for one publication. */
    @Synchronized
    fun countSince(since: Instant, publicationId: String? = null): Int =
        reports.count { it.createdAt >= since && (publicationId == null || it.publicationId == publicationId) }
}
