package co.edu.uniandes.unieat.data.myreports

import co.edu.uniandes.unieat.core.model.ReportedPlaceResponse
import co.edu.uniandes.unieat.core.model.ReportedPlacesResponse
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import java.time.Instant

/** Mis reportes: the places where the student reported, and their reports in one place. */
interface MyReportsRepository {
    suspend fun places(): ReportedPlacesResponse

    suspend fun place(establishmentId: String): ReportedPlaceResponse
}

class RemoteMyReportsRepository(private val api: ApiClient) : MyReportsRepository {
    override suspend fun places(): ReportedPlacesResponse = api.get("me/reports/places")

    override suspend fun place(establishmentId: String): ReportedPlaceResponse =
        api.get("me/reports/places/$establishmentId")
}

/** Demo mode: no signed-in student, nothing reported. */
object EmptyMyReportsRepository : MyReportsRepository {
    override suspend fun places() = ReportedPlacesResponse(Instant.now(), emptyList())

    override suspend fun place(establishmentId: String): ReportedPlaceResponse =
        throw ApiException(ApiError("NOT_FOUND", "No tienes reportes en este restaurante."))
}
