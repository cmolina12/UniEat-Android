package co.edu.uniandes.unieat

import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.remote.AccessTokenProvider
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.repository.ReportRepository

/** Release builds never ship fake data: AppContainer always falls back to the remote API. */
object DevDataSource {
    fun menuRepository(): MenuRepository? = null

    fun analyticsRepository(): AnalyticsRepository? = null

    fun reportRepository(): ReportRepository? = null

    /** Release has no test account: requests fail with AUTH_REQUIRED until the Login feature exists. */
    fun tokenProvider(config: SupabaseConfig): AccessTokenProvider? = null

    val fixtures: List<DemoFixture> = emptyList()

    val seedFixtures: List<DemoFixture> = emptyList()
}
