package co.edu.uniandes.unieat.data.repository

import co.edu.uniandes.unieat.core.model.ReportBody
import co.edu.uniandes.unieat.core.model.ReportResponse
import co.edu.uniandes.unieat.data.remote.ApiClient
import io.ktor.http.HttpMethod

interface ReportRepository {
    suspend fun submit(body: ReportBody): ReportResponse
}

class RemoteReportRepository(private val api: ApiClient) : ReportRepository {
    override suspend fun submit(body: ReportBody): ReportResponse =
        api.send(HttpMethod.Post, "reports", body)
}
