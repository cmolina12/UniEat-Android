package co.edu.uniandes.unieat.data.repository

import co.edu.uniandes.unieat.core.model.EstablishmentRequestBody
import co.edu.uniandes.unieat.core.model.EstablishmentRequestResponse
import co.edu.uniandes.unieat.core.model.EstablishmentsResponse
import co.edu.uniandes.unieat.core.model.OwnEstablishment
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import io.ktor.http.HttpMethod

interface EstablishmentRepository {
    /** GET /establishments/mine — the user's establishments, approved or still pending. */
    suspend fun mine(): List<OwnEstablishment>

    /** POST /establishments — the request stays pending until an admin approves it. Returns the server message. */
    suspend fun request(body: EstablishmentRequestBody): String
}

class RemoteEstablishmentRepository(private val api: ApiClient) : EstablishmentRepository {
    override suspend fun mine(): List<OwnEstablishment> =
        api.get<EstablishmentsResponse>("establishments/mine").establishments

    override suspend fun request(body: EstablishmentRequestBody): String =
        api.send<EstablishmentRequestResponse, EstablishmentRequestBody>(HttpMethod.Post, "establishments", body).message
}

/** Demo data simulates a student, who manages no establishment. */
class FakeEstablishmentRepository : EstablishmentRepository {
    override suspend fun mine(): List<OwnEstablishment> = emptyList()

    override suspend fun request(body: EstablishmentRequestBody): String = throw ApiException(
        ApiError(ApiException.FORBIDDEN, "Los datos de prueba no envían solicitudes."),
        403,
    )
}
