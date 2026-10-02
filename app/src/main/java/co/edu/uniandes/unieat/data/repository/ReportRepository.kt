package co.edu.uniandes.unieat.data.repository

import co.edu.uniandes.unieat.core.model.ReportBody
import co.edu.uniandes.unieat.core.model.ReportResponse
import co.edu.uniandes.unieat.data.remote.ApiClient
import io.ktor.http.HttpMethod

/**
 * Community reports. Implementations throw [co.edu.uniandes.unieat.data.remote.ApiException]
 * (429 RATE_LIMITED, 403 FORBIDDEN for the menu's owner, 404 for an unknown version...).
 */
interface ReportRepository {
    /**
     * POST /reports — tied to the exact version the student saw; never edits the official menu.
     * Discrepancies (unavailable, price, location) come back `pending`; observations `observation`.
     */
    suspend fun submit(body: ReportBody): ReportResponse
}

class RemoteReportRepository(private val api: ApiClient) : ReportRepository {
    override suspend fun submit(body: ReportBody): ReportResponse =
        api.send(HttpMethod.Post, "reports", body)
}
