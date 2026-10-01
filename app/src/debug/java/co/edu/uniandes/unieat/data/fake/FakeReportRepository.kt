package co.edu.uniandes.unieat.data.fake

import co.edu.uniandes.unieat.core.model.ReportBody
import co.edu.uniandes.unieat.core.model.ReportResponse
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.ReportRepository
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * Debug [ReportRepository] that follows the api-v1 rules for a student: validation, rate limits
 * (3 per menu every 10 min, 20 per hour) and the same messages as handlers/reports.ts.
 */
class FakeReportRepository(
    private val latencyMillis: Long = 600,
    private val clock: () -> Instant = Instant::now,
) : ReportRepository {

    override suspend fun submit(body: ReportBody): ReportResponse {
        delay(latencyMillis)
        val now = clock()

        if ((body.note?.length ?: 0) > 280) throw validation("note")
        if (body.observedWaitMinutes != null && body.observedWaitMinutes !in 0..120) throw validation("observedWaitMinutes")
        FakeBackend.menus(now).firstOrNull { it.id == body.publicationId && it.version == body.version }
            ?: throw ApiException(ApiError(ApiException.NOT_FOUND, "La versión reportada no existe."), 404)

        val perMenu = FakeBackend.countSince(now - Duration.ofMinutes(10), body.publicationId)
        val perHour = FakeBackend.countSince(now - Duration.ofHours(1))
        if (perMenu >= 3 || perHour >= 20) {
            throw ApiException(ApiError(ApiException.RATE_LIMITED, "Ya enviaste varios reportes. Intenta más tarde."), 429)
        }

        val report = FakeBackend.addReport(body.publicationId, body.version, body.kind, now)
        return ReportResponse(
            reportId = report.id,
            publicationId = report.publicationId,
            version = report.version,
            kind = report.kind,
            status = report.status,
            createdAt = report.createdAt,
            message = if (report.status == "pending") {
                "Reporte recibido. Queda pendiente hasta que se revise; el menú oficial no cambia."
            } else {
                "Observación registrada. Gracias."
            },
        )
    }

    private fun validation(field: String) =
        ApiException(ApiError(ApiException.VALIDATION_ERROR, "Revisa el campo $field.", field = field), 400)
}
