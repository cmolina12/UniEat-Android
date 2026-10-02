package co.edu.uniandes.unieat

import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.auth.DevTokenProvider
import co.edu.uniandes.unieat.data.fake.FakeMenuRepository
import co.edu.uniandes.unieat.data.fake.FakeReportRepository
import co.edu.uniandes.unieat.data.fake.LoggingAnalyticsRepository
import co.edu.uniandes.unieat.data.fake.SeedMenus
import co.edu.uniandes.unieat.data.remote.AccessTokenProvider
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.repository.ReportRepository

/** Debug-only data source. The release source set has a stub with the same API that returns nothing. */
object DevDataSource {
    fun menuRepository(): MenuRepository = FakeMenuRepository()

    fun analyticsRepository(): AnalyticsRepository = LoggingAnalyticsRepository()

    fun reportRepository(): ReportRepository = FakeReportRepository()

    /** Signs in with the dev account (BuildConfig.DEV_EMAIL) when a backend is configured. */
    fun tokenProvider(config: SupabaseConfig): AccessTokenProvider? =
        if (config.isConfigured) DevTokenProvider(config, BuildConfig.DEV_EMAIL, BuildConfig.DEV_PASSWORD) else null

    /** All sample menus, including the fake-only BQ-05 cases (0004–0006). */
    val fixtures: List<DemoFixture> = SeedMenus.fixtures

    /** Only the menus that really exist in backend seed.sql (0001–0003). */
    val seedFixtures: List<DemoFixture> = SeedMenus.seedFixtures
}
