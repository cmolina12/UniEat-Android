package co.edu.uniandes.unieat

import co.edu.uniandes.unieat.data.repository.MenuRepository

/** Release builds never ship fake data: AppContainer always falls back to the remote API. */
object DevDataSource {
    fun menuRepository(): MenuRepository? = null

    val fixtures: List<DemoFixture> = emptyList()
}
