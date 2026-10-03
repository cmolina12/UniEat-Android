package co.edu.uniandes.unieat.data.repository

import co.edu.uniandes.unieat.core.model.MeResponse
import co.edu.uniandes.unieat.data.remote.ApiClient

interface ProfileRepository {
    suspend fun me(): MeResponse
}

class RemoteProfileRepository(private val api: ApiClient) : ProfileRepository {
    override suspend fun me(): MeResponse = api.get("me")
}

class FakeProfileRepository(
    private val profile: MeResponse = MeResponse(
        id = "demo-user",
        displayName = "Usuario demo",
        role = "admin",
    ),
) : ProfileRepository {
    override suspend fun me(): MeResponse = profile
}
