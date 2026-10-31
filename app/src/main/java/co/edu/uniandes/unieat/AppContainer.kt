package co.edu.uniandes.unieat

import android.content.Context
import androidx.collection.LruCache
import androidx.room.Room
import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.cache.MenuDetailCache
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
import co.edu.uniandes.unieat.data.local.FileOfflineStore
import co.edu.uniandes.unieat.data.local.FilePerformanceSnapshots
import co.edu.uniandes.unieat.data.local.OfflineStore
import co.edu.uniandes.unieat.data.local.PerformancePreferences
import co.edu.uniandes.unieat.data.local.PerformanceSnapshots
import co.edu.uniandes.unieat.data.local.ProfileCache
import co.edu.uniandes.unieat.data.local.ReportQueue
import co.edu.uniandes.unieat.data.local.settingsDataStore
import co.edu.uniandes.unieat.data.local.UniEatDatabase
import co.edu.uniandes.unieat.data.local.WaitHistoryDatabase
import co.edu.uniandes.unieat.data.local.clearArrival
import co.edu.uniandes.unieat.data.local.clearPendingMinutes
import co.edu.uniandes.unieat.data.local.clearSentReports
import co.edu.uniandes.unieat.data.local.WorkManagerReportScheduler
import co.edu.uniandes.unieat.data.location.FusedLocationRepository
import co.edu.uniandes.unieat.data.network.ConnectionType
import co.edu.uniandes.unieat.data.network.ConnectivityMonitor
import co.edu.uniandes.unieat.data.pilot.CachePilot
import co.edu.uniandes.unieat.data.pilot.CachePilotRepository
import co.edu.uniandes.unieat.data.pilot.CachePilotUploader
import co.edu.uniandes.unieat.data.pilot.DataStorePilotEnrollment
import co.edu.uniandes.unieat.data.pilot.LocalCachePilotRepository
import co.edu.uniandes.unieat.data.pilot.PilotEventTracker
import co.edu.uniandes.unieat.data.pilot.RemoteCachePilotRepository
import co.edu.uniandes.unieat.data.pilot.WorkManagerCachePilotScheduler
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

    /** DataStore: last GET /me, so role and tabs work when opening offline. */
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

    /** Recently opened details in memory (LruCache, 20). */
    val menuDetailCache = MenuDetailCache()

    /** Arrival time per menu, 0L when there is none. 20 because the campus has few restaurants (17 in the seeds, about 8 active). */
    val arrivalCache = LruCache<String, Long>(20)

    val waitRecords by lazy {
        Room.databaseBuilder(context.applicationContext, WaitHistoryDatabase::class.java, "wait_history.db").build().records()
    }

    /** Offline copy of the feed. Not needed in demo mode. */
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

    /** Room database. For now it has the reports waiting for signal. */
    private val database: UniEatDatabase by lazy { UniEatDatabase.build(context) }

    val reportScheduler: FlushScheduler by lazy { WorkManagerReportScheduler(context) }

    /** DataStore: Rendimiento period. */
    val performancePreferences: PerformancePreferences by lazy { DataStorePerformancePreferences(context.settingsDataStore) }

    /** Last Rendimiento result per role and period, for offline. */
    val performanceSnapshots: PerformanceSnapshots by lazy { FilePerformanceSnapshots(File(context.filesDir, "performance")) }

    /** Saved lists and drafts of Publicar, Mis establecimientos and Admin. */
    val offlineStore: OfflineStore? by lazy {
        if (usesFakeData) null else FileOfflineStore(File(context.filesDir, "offline"))
    }

    val reportQueue: ReportQueue by lazy { ReportQueue(database.pendingReports(), reportRepository, reportScheduler) }

    val analyticsRepository: AnalyticsRepository by lazy {
        (if (usesFakeData) DevDataSource.analyticsRepository() else null) ?: RemoteAnalyticsRepository(apiClient)
    }

    private val eventQueue by lazy { EventQueue(File(context.filesDir, "analytics/pending-events.json")) }

    val flushScheduler: FlushScheduler by lazy { WorkManagerFlushScheduler(context) }

    val eventUploader: EventUploader by lazy { EventUploader(eventQueue, analyticsRepository) }

    /**
     * Decorator (Samuel, BQ-13): every event still goes to the analytics queue unchanged; the pilot also
     * hears about it, so a "selection" marks the current pilot visit without touching the screens.
     */
    val eventTracker: EventTracker by lazy {
        val queued = QueuedEventTracker(eventQueue, flushScheduler, appScope, appVersion = BuildConfig.VERSION_NAME)
        PilotEventTracker(queued) { kind ->
            val at = System.currentTimeMillis()
            appScope.launch { cachePilot.onInteraction(kind, at) }
        }
    }

    // ---- BQ-13 cache pilot (Samuel) -------------------------------------------------------------
    // Enrollment in DataStore "unieat_settings", one row per visit in Room, upload with WorkManager,
    // answer from the backend with this device's rows as fallback.

    val cachePilotScheduler: FlushScheduler by lazy { WorkManagerCachePilotScheduler(context) }

    val cachePilot: CachePilot by lazy {
        CachePilot(
            enrollment = DataStorePilotEnrollment(context.settingsDataStore),
            dao = database.cachePilotSessions(),
            // Demo mode has no Supabase session; its profile is "demo-user", so the pilot can be shown offline too.
            currentUser = {
                (sessionManager.state.value as? AuthState.SignedIn)?.session?.userId ?: DEMO_USER.takeIf { usesFakeData }
            },
            connectionType = { ConnectionType.current(context) },
            onVisitEnded = { cachePilotScheduler.schedule() },
        )
    }

    val cachePilotRepository: CachePilotRepository by lazy {
        val local = LocalCachePilotRepository(cachePilot)
        if (usesFakeData) local else RemoteCachePilotRepository(apiClient, local)
    }

    val cachePilotUploader: CachePilotUploader by lazy { CachePilotUploader(cachePilot, cachePilotRepository) }

    /**
     * Called once per launch. Ending the visits of the previous launch is local work, so it happens now on
     * IO, with or without signal; only the upload waits for the network (WorkManager). Doing the closing
     * inside the worker would leave visits open while a previous upload is still in its retry backoff.
     */
    fun startCachePilot() {
        appScope.launch {
            cachePilot.closeFinishedSessions()
            cachePilotScheduler.schedule()
        }
    }

    init {
        // the saved feed belongs to the user who loaded it
        appScope.launch {
            sessionManager.state.filterIsInstance<AuthState.SignedOut>().collect {
                feedCache?.clear()
                menuDetailCache.clear()
                // otherwise queued reports would go out with the next account's session
                reportQueue.clear()
                performanceSnapshots.clear()
                // one account's drafts and lists can't show up for the next one
                offlineStore?.clear()
                profileCache?.clear()
                // otherwise a pending wait measurement would go out with the next account's session
                if (!usesFakeData) {
                    clearArrival(context)
                    clearPendingMinutes(context)
                    arrivalCache.evictAll()
                    waitRecords.deleteAll()
                    clearSentReports(context)
                }
                // BQ-13: the pilot consent and its visits belong to the account that signed out. Demo mode
                // is always "signed out", so clearing there would wipe the demo pilot on every launch.
                if (!usesFakeData) cachePilot.clear()
            }
        }
    }

    private companion object {
        /** Same id as FakeProfileRepository's demo profile. */
        const val DEMO_USER = "demo-user"
    }
}
