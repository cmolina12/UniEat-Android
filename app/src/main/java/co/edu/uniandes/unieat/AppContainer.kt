package co.edu.uniandes.unieat

import android.content.Context
import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.analytics.EventQueue
import co.edu.uniandes.unieat.data.analytics.EventTracker
import co.edu.uniandes.unieat.data.analytics.EventUploader
import co.edu.uniandes.unieat.data.analytics.FlushScheduler
import co.edu.uniandes.unieat.data.analytics.QueuedEventTracker
import co.edu.uniandes.unieat.data.analytics.RemoteAnalyticsRepository
import co.edu.uniandes.unieat.data.analytics.WorkManagerFlushScheduler
import co.edu.uniandes.unieat.data.location.FusedLocationRepository
import co.edu.uniandes.unieat.data.location.LocationRepository
import co.edu.uniandes.unieat.data.remote.AccessTokenProvider
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.repository.RemoteMenuRepository
import co.edu.uniandes.unieat.data.repository.RemoteReportRepository
import co.edu.uniandes.unieat.data.repository.ReportRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

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

    val reportRepository: ReportRepository by lazy {
        (if (usesFakeData) DevDataSource.reportRepository() else null) ?: RemoteReportRepository(apiClient)
    }

    // Analytics pipeline: EventTracker → EventQueue (disk) → WorkManager → EventUploader → POST /events/batch.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val analyticsRepository: AnalyticsRepository by lazy {
        (if (usesFakeData) DevDataSource.analyticsRepository() else null) ?: RemoteAnalyticsRepository(apiClient)
    }

    private val eventQueue by lazy { EventQueue(File(context.filesDir, "analytics/pending-events.json")) }

    val flushScheduler: FlushScheduler by lazy { WorkManagerFlushScheduler(context) }

    val eventUploader: EventUploader by lazy { EventUploader(eventQueue, analyticsRepository) }

    val eventTracker: EventTracker by lazy {
        QueuedEventTracker(eventQueue, flushScheduler, appScope, appVersion = BuildConfig.VERSION_NAME)
    }

    private companion object {
        /**
         * Switch for development: true = debug builds use FakeMenuRepository (no login needed);
         * false = always call api-v1. Release builds ignore it because their DevDataSource is empty.
         */
        const val USE_FAKE_DATA = true
    }
}
