package co.edu.uniandes.unieat

import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.data.remote.AccessTokenProvider
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.repository.RemoteMenuRepository

/** Manual dependency injection: one instance per app, ViewModels get dependencies from here. */
class AppContainer(
    val config: SupabaseConfig = SupabaseConfig.fromBuildConfig(),
) {
    // Replaced by the Supabase Auth session when the Login feature is built.
    private val tokenProvider = AccessTokenProvider { throw ApiException.authRequired() }

    val apiClient: ApiClient by lazy { ApiClient(config, tokenProvider) }

    val menuRepository: MenuRepository by lazy { RemoteMenuRepository(apiClient) }
}
