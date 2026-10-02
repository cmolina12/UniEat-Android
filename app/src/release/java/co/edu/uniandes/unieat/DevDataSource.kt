package co.edu.uniandes.unieat

import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.repository.ReportRepository

/** Release builds never ship fake data: AppContainer always falls back to the remote API. */
object DevDataSource {
    fun menuRepository(): MenuRepository? = null

    fun analyticsRepository(): AnalyticsRepository? = null

    fun reportRepository(): ReportRepository? = null

    val fixtures: List<DemoFixture> = emptyList()
}
