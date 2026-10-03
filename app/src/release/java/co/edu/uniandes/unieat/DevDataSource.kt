package co.edu.uniandes.unieat

import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.repository.ReportRepository

object DevDataSource {
    fun menuRepository(): MenuRepository? = null

    fun analyticsRepository(): AnalyticsRepository? = null

    fun reportRepository(): ReportRepository? = null

    val fixtures: List<DemoFixture> = emptyList()

    val seedFixtures: List<DemoFixture> = emptyList()
}
