package co.edu.uniandes.unieat

import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.fake.FakeMenuRepository
import co.edu.uniandes.unieat.data.fake.FakeReportRepository
import co.edu.uniandes.unieat.data.fake.LoggingAnalyticsRepository
import co.edu.uniandes.unieat.data.fake.SeedMenus
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.repository.ReportRepository

/** Debug-only data source used when Supabase is not configured. */
object DevDataSource {
    fun menuRepository(): MenuRepository = FakeMenuRepository()

    fun analyticsRepository(): AnalyticsRepository = LoggingAnalyticsRepository()

    fun reportRepository(): ReportRepository = FakeReportRepository()

    /** All sample menus, including the fake-only BQ-05 cases (0004–0006). */
    val fixtures: List<DemoFixture> = SeedMenus.fixtures

    /** Only the menus that really exist in backend seed.sql (0001–0003). */
    val seedFixtures: List<DemoFixture> = SeedMenus.seedFixtures
}
