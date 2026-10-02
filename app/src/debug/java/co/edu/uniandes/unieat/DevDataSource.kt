package co.edu.uniandes.unieat

import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.fake.FakeMenuRepository
import co.edu.uniandes.unieat.data.fake.FakeReportRepository
import co.edu.uniandes.unieat.data.fake.LoggingAnalyticsRepository
import co.edu.uniandes.unieat.data.fake.SeedMenus
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.repository.ReportRepository

/** Debug-only data source. The release source set has a stub with the same API that returns nothing. */
object DevDataSource {
    fun menuRepository(): MenuRepository = FakeMenuRepository()

    fun analyticsRepository(): AnalyticsRepository = LoggingAnalyticsRepository()

    fun reportRepository(): ReportRepository = FakeReportRepository()

    val fixtures: List<DemoFixture> = SeedMenus.fixtures
}
