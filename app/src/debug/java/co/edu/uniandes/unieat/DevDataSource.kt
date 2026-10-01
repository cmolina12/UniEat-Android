package co.edu.uniandes.unieat

import co.edu.uniandes.unieat.data.fake.FakeMenuRepository
import co.edu.uniandes.unieat.data.fake.SeedMenus
import co.edu.uniandes.unieat.data.repository.MenuRepository

/** Debug-only data source. The release source set has a stub with the same API that returns nothing. */
object DevDataSource {
    fun menuRepository(): MenuRepository = FakeMenuRepository()

    val fixtures: List<DemoFixture> = SeedMenus.fixtures
}
