package co.edu.uniandes.unieat

import android.content.Context
import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.auth.SessionManager
import co.edu.uniandes.unieat.data.auth.SessionStore
import co.edu.uniandes.unieat.data.auth.SupabaseAuthRepository
import co.edu.uniandes.unieat.data.analytics.EventQueue
import co.edu.uniandes.unieat.data.analytics.EventTracker
import co.edu.uniandes.unieat.data.analytics.EventUploader
import co.edu.uniandes.unieat.data.analytics.FlushScheduler
import co.edu.uniandes.unieat.data.analytics.QueuedEventTracker
import co.edu.uniandes.unieat.data.analytics.RemoteAnalyticsRepository
import co.edu.uniandes.unieat.data.analytics.WorkManagerFlushScheduler
import co.edu.uniandes.unieat.data.location.FusedLocationRepository
import co.edu.uniandes.unieat.data.telemetry.FeedLoadRepository
import co.edu.uniandes.unieat.data.telemetry.FeedLoadTelemetry
import co.edu.uniandes.unieat.data.telemetry.FeedLoadUploader
import co.edu.uniandes.unieat.data.telemetry.LocalFeedLoadRepository
import co.edu.uniandes.unieat.data.telemetry.RemoteFeedLoadRepository
import co.edu.uniandes.unieat.data.telemetry.WorkManagerFeedLoadScheduler
import co.edu.uniandes.unieat.data.location.LocationRepository
import co.edu.uniandes.unieat.data.remote.AccessTokenProvider
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.repository.RemoteMenuRepository
import co.edu.uniandes.unieat.data.repository.RemoteReportRepository
import co.edu.uniandes.unieat.data.repository.ReportRepository
import co.edu.uniandes.unieat.data.repository.ProfileRepository
import co.edu.uniandes.unieat.data.repository.RemoteProfileRepository
import co.edu.uniandes.unieat.data.repository.FakeProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Manual dependency injection: one instance per app, ViewModels get dependencies from here. */
class AppContainer(
    private val context: Context,
    val config: SupabaseConfig = SupabaseConfig.fromBuildConfig(),
) {
    val authRepository by lazy { SupabaseAuthRepository(config) }
    val sessionStore by lazy { SessionStore(context) }
    val sessionManager by lazy { SessionManager(authRepository, sessionStore) }

    // Demo mode still bypasses the remote API. Configured builds use the real persisted Supabase session.
    private val tokenProvider: AccessTokenProvider by lazy { sessionManager }

    val apiClient: ApiClient by lazy { ApiClient(config, tokenProvider) }
    val profileRepository: ProfileRepository by lazy {
        if (usesFakeData) FakeProfileRepository() else RemoteProfileRepository(apiClient)
    }

    // Automatic: debug builds without supabase.url in local.properties run on fake data;
    // with a backend configured they call api-v1. Release never has fake data.
    private val fakeMenuRepository: MenuRepository? = if (!config.isConfigured) DevDataSource.menuRepository() else null

    /** True when screens run on debug fake data (seed.sql copy) instead of the API. Always false in release. */
    val usesFakeData: Boolean get() = fakeMenuRepository != null

    val menuRepository: MenuRepository by lazy { fakeMenuRepository ?: RemoteMenuRepository(apiClient) }

    val locationRepository: LocationRepository by lazy { FusedLocationRepository(context) }

    // BQ-01: FeedLoadTelemetry (disk) → WorkManager → FeedLoadUploader → POST /telemetry/feed-loads.
    val feedLoadTelemetry: FeedLoadTelemetry by lazy {
        FeedLoadTelemetry(context, onSettled = { feedLoadScheduler.schedule() })
    }

    val feedLoadScheduler: FlushScheduler by lazy { WorkManagerFeedLoadScheduler(context) }

    val feedLoadRepository: FeedLoadRepository by lazy {
        val local = LocalFeedLoadRepository(feedLoadTelemetry)
        if (usesFakeData) local else RemoteFeedLoadRepository(apiClient, local)
    }

    val feedLoadUploader: FeedLoadUploader by lazy { FeedLoadUploader(feedLoadTelemetry, feedLoadRepository) }

    /** Feed filters shared by "Hoy" and "Elige por mí": both ViewModels observe this same flow. */
    val feedFilters = MutableStateFlow(FeedFilters())

    val reportRepository: ReportRepository by lazy {
        (if (usesFakeData) DevDataSource.reportRepository() else null) ?: RemoteReportRepository(apiClient)
    }

    // Analytics pipeline: EventTracker → EventQueue (disk) → WorkManager → EventUploader → POST /events/batch.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val analyticsRepository: AnalyticsRepository by lazy {
        (if (usesFakeData) DevDataSource.analyticsRepository() else null) ?: RemoteAnalyticsRepository(apiClient)
    }

    private val eventQueue by lazy { EventQueue(File(context.filesDir, "analytics/pending-events.json")) }

    val flushScheduler: FlushScheduler by lazy { WorkManagerFlushScheduler(context) }

    val eventUploader: EventUploader by lazy { EventUploader(eventQueue, analyticsRepository) }

    val eventTracker: EventTracker by lazy {
        QueuedEventTracker(eventQueue, flushScheduler, appScope, appVersion = BuildConfig.VERSION_NAME)
    }
}
