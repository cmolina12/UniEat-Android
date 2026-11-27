package co.edu.uniandes.unieat.data.myreports

import co.edu.uniandes.unieat.core.model.ReportedPlaceResponse
import co.edu.uniandes.unieat.core.model.ReportedPlacesResponse
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import java.time.Instant

/** Mis reportes: the places where the student reported, and their reports in one place. */
interface MyReportsRepository {
    /** [opened] = the screen just opened (BQ-17 counts it); reloads pass false. */
    suspend fun places(opened: Boolean = false): ReportedPlacesResponse

    suspend fun place(establishmentId: String): ReportedPlaceResponse
}

class RemoteMyReportsRepository(private val api: ApiClient) : MyReportsRepository {
    override suspend fun places(opened: Boolean): ReportedPlacesResponse =
        api.get("me/reports/places", if (opened) mapOf("opened" to 1) else emptyMap())

    override suspend fun place(establishmentId: String): ReportedPlaceResponse =
        api.get("me/reports/places/$establishmentId")
}

/** Demo mode: no signed-in student, nothing reported. */
object EmptyMyReportsRepository : MyReportsRepository {
    override suspend fun places(opened: Boolean) = ReportedPlacesResponse(Instant.now(), emptyList())

    override suspend fun place(establishmentId: String): ReportedPlaceResponse =
        throw ApiException(ApiError("NOT_FOUND", "No tienes reportes en este restaurante."))
}
