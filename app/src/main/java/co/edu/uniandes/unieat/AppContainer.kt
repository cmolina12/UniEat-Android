package co.edu.uniandes.unieat

import android.content.Context
import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.auth.AuthState
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
import co.edu.uniandes.unieat.data.local.DataStorePerformancePreferences
import co.edu.uniandes.unieat.data.local.DataStoreProfileCache
import co.edu.uniandes.unieat.data.local.FilePerformanceSnapshots
import co.edu.uniandes.unieat.data.local.PerformancePreferences
import co.edu.uniandes.unieat.data.local.PerformanceSnapshots
import co.edu.uniandes.unieat.data.local.ProfileCache
import co.edu.uniandes.unieat.data.local.ReportQueue
import co.edu.uniandes.unieat.data.local.settingsDataStore
import co.edu.uniandes.unieat.data.local.UniEatDatabase
import co.edu.uniandes.unieat.data.local.WorkManagerReportScheduler
import co.edu.uniandes.unieat.data.location.FusedLocationRepository
import co.edu.uniandes.unieat.data.network.ConnectivityMonitor
import co.edu.uniandes.unieat.data.telemetry.FeedLoadRepository
import co.edu.uniandes.unieat.data.telemetry.FeedLoadTelemetry
import co.edu.uniandes.unieat.data.telemetry.FeedLoadUploader
import co.edu.uniandes.unieat.data.telemetry.LocalFeedLoadRepository
import co.edu.uniandes.unieat.data.telemetry.RemoteFeedLoadRepository
import co.edu.uniandes.unieat.data.telemetry.WorkManagerFeedLoadScheduler
import co.edu.uniandes.unieat.data.location.LocationRepository
import co.edu.uniandes.unieat.data.remote.AccessTokenProvider
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.repository.FeedCache
import co.edu.uniandes.unieat.data.repository.FileFeedCache
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.repository.RemoteMenuRepository
import co.edu.uniandes.unieat.data.repository.RemoteReportRepository
import co.edu.uniandes.unieat.data.repository.ReportRepository
import co.edu.uniandes.unieat.data.repository.ProfileRepository
import co.edu.uniandes.unieat.data.repository.RemoteProfileRepository
import co.edu.uniandes.unieat.data.repository.FakeProfileRepository
import co.edu.uniandes.unieat.data.repository.EstablishmentRepository
import co.edu.uniandes.unieat.data.repository.AdminRepository
import co.edu.uniandes.unieat.data.repository.FakeAdminRepository
import co.edu.uniandes.unieat.data.repository.RemoteAdminRepository
import co.edu.uniandes.unieat.data.location.AddressGeocoder
import co.edu.uniandes.unieat.data.location.AndroidAddressGeocoder
import co.edu.uniandes.unieat.data.repository.FakeEstablishmentRepository
import co.edu.uniandes.unieat.data.repository.RemoteEstablishmentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import java.io.File

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

    /** DataStore "unieat_settings": last GET /me, so the role (and tabs) survive opening offline. */
    val profileCache: ProfileCache? by lazy {
        if (usesFakeData) null else DataStoreProfileCache(context.settingsDataStore)
    }

    val establishmentRepository: EstablishmentRepository by lazy {
        if (usesFakeData) FakeEstablishmentRepository() else RemoteEstablishmentRepository(apiClient)
    }

    val adminRepository: AdminRepository by lazy {
        if (usesFakeData) FakeAdminRepository() else RemoteAdminRepository(apiClient)
    }

    val addressGeocoder: AddressGeocoder by lazy { AndroidAddressGeocoder(context) }

    private val fakeMenuRepository: MenuRepository? = if (!config.isConfigured) DevDataSource.menuRepository() else null

    val usesFakeData: Boolean get() = fakeMenuRepository != null

    val menuRepository: MenuRepository by lazy { fakeMenuRepository ?: RemoteMenuRepository(apiClient) }

    val locationRepository: LocationRepository by lazy { FusedLocationRepository(context) }

    val connectivity: ConnectivityMonitor by lazy { ConnectivityMonitor(context) }

    /** Offline copy of the feed. Demo mode has no network, so it does not need one. */
    val feedCache: FeedCache? by lazy {
        if (usesFakeData) null else FileFeedCache(File(context.filesDir, "feed-cache"))
    }

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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Local relational database (Room). Today it holds the reports waiting for a connection. */
    private val database: UniEatDatabase by lazy { UniEatDatabase.build(context) }

    val reportScheduler: FlushScheduler by lazy { WorkManagerReportScheduler(context) }

    /** DataStore "unieat_settings": the Rendimiento period. */
    val performancePreferences: PerformancePreferences by lazy { DataStorePerformancePreferences(context.settingsDataStore) }

    /** Last Rendimiento result per role and period, for offline. */
    val performanceSnapshots: PerformanceSnapshots by lazy { FilePerformanceSnapshots(File(context.filesDir, "performance")) }

    val reportQueue: ReportQueue by lazy { ReportQueue(database.pendingReports(), reportRepository, reportScheduler) }

    val analyticsRepository: AnalyticsRepository by lazy {
        (if (usesFakeData) DevDataSource.analyticsRepository() else null) ?: RemoteAnalyticsRepository(apiClient)
    }

    private val eventQueue by lazy { EventQueue(File(context.filesDir, "analytics/pending-events.json")) }

    val flushScheduler: FlushScheduler by lazy { WorkManagerFlushScheduler(context) }

    val eventUploader: EventUploader by lazy { EventUploader(eventQueue, analyticsRepository) }

    val eventTracker: EventTracker by lazy {
        QueuedEventTracker(eventQueue, flushScheduler, appScope, appVersion = BuildConfig.VERSION_NAME)
    }

    init {
        // The saved feed belongs to the user who loaded it.
        appScope.launch {
            sessionManager.state.filterIsInstance<AuthState.SignedOut>().collect {
                feedCache?.clear()
                // Waiting reports would otherwise be sent with the next account's session.
                reportQueue.clear()
                performanceSnapshots.clear()
                profileCache?.clear()
            }
        }
    }
}
