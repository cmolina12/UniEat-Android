package co.edu.uniandes.unieat

import android.content.Context
import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.data.location.FusedLocationRepository
import co.edu.uniandes.unieat.data.location.LocationRepository
import co.edu.uniandes.unieat.data.remote.AccessTokenProvider
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.repository.RemoteMenuRepository

/** Manual dependency injection: one instance per app, ViewModels get dependencies from here. */
class AppContainer(
    private val context: Context,
    val config: SupabaseConfig = SupabaseConfig.fromBuildConfig(),
) {
    // Replaced by the Supabase Auth session when the Login feature is built.
    private val tokenProvider = AccessTokenProvider { throw ApiException.authRequired() }

    val apiClient: ApiClient by lazy { ApiClient(config, tokenProvider) }

    private val fakeMenuRepository: MenuRepository? = if (USE_FAKE_DATA) DevDataSource.menuRepository() else null

    /** True when screens run on debug fake data (seed.sql) instead of the API. Always false in release. */
    val usesFakeData: Boolean get() = fakeMenuRepository != null

    val menuRepository: MenuRepository by lazy { fakeMenuRepository ?: RemoteMenuRepository(apiClient) }

    val locationRepository: LocationRepository by lazy { FusedLocationRepository(context) }

    private companion object {
        /**
         * Switch for development: true = debug builds use FakeMenuRepository (no login needed);
         * false = always call api-v1. Release builds ignore it because their DevDataSource is empty.
         */
        const val USE_FAKE_DATA = true
    }
}
