package co.edu.uniandes.unieat.data.repository

import co.edu.uniandes.unieat.core.model.EstablishmentsResponse
import co.edu.uniandes.unieat.core.model.OwnEstablishment
import co.edu.uniandes.unieat.data.remote.ApiClient

interface EstablishmentRepository {
    /** GET /establishments/mine — the user's establishments, approved or still pending. */
    suspend fun mine(): List<OwnEstablishment>
}

class RemoteEstablishmentRepository(private val api: ApiClient) : EstablishmentRepository {
    override suspend fun mine(): List<OwnEstablishment> =
        api.get<EstablishmentsResponse>("establishments/mine").establishments
}

/** Demo data simulates a student, who manages no establishment. */
class FakeEstablishmentRepository : EstablishmentRepository {
    override suspend fun mine(): List<OwnEstablishment> = emptyList()
}
