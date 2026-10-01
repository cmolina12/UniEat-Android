package co.edu.uniandes.unieat.ui.detail

import co.edu.uniandes.unieat.core.model.ReportBody
import co.edu.uniandes.unieat.core.model.ReportResponse
import co.edu.uniandes.unieat.data.repository.ReportRepository
import java.time.Instant

/** Test double: records bodies; [result] decides the answer (throw for errors). */
internal class StubReports(
    var result: (ReportBody) -> ReportResponse = { body ->
        val pending = body.kind.name in setOf("UNAVAILABLE", "PRICE", "LOCATION")
        ReportResponse(
            "r1", body.publicationId, body.version, body.kind,
            if (pending) "pending" else "observation", Instant.parse("2026-09-29T17:00:00Z"),
            if (pending) "Reporte recibido. Queda pendiente hasta que se revise; el menú oficial no cambia." else "Observación registrada. Gracias.",
        )
    },
) : ReportRepository {
    val sent = mutableListOf<ReportBody>()

    override suspend fun submit(body: ReportBody): ReportResponse {
        sent += body
        return result(body)
    }
}
