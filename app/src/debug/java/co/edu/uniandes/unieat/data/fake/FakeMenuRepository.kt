package co.edu.uniandes.unieat.data.fake

import co.edu.uniandes.unieat.core.model.CloseResponse
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.FeedResponse
import co.edu.uniandes.unieat.core.model.MenuBody
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * In-memory [MenuRepository] for development without login. Answers like api-v1 does for a student:
 * 404 for unknown ids and 410 GONE for expired menus. Owner-only operations are rejected.
 */
class FakeMenuRepository(
    private val latencyMillis: Long = 400,
    private val clock: () -> Instant = Instant::now,
) : MenuRepository {

    override suspend fun feed(filters: FeedFilters): FeedResponse {
        delay(latencyMillis)
        val now = clock()
        val menus = FakeBackend.menus(now).filter { it.closedAt == null && it.isActive(now) }
        return FeedResponse(serverNow = now, fetchedAt = now, menus = menus, resultCount = menus.size)
    }

    override suspend fun menu(id: String): MenuDetailResponse {
        delay(latencyMillis)
        val now = clock()
        val menu = FakeBackend.menus(now).firstOrNull { it.id == id }
            ?: throw ApiException(ApiError(ApiException.NOT_FOUND, "No encontramos ese menú."), 404)
        if (menu.closedAt != null || !menu.isActive(now)) {
            val closed = menu.closedAt != null
            throw ApiException(
                ApiError(ApiException.GONE, if (closed) "Este menú fue cerrado" else "Este menú ya venció"),
                410,
            )
        }
        return MenuDetailResponse(serverNow = now, fetchedAt = now, menu = menu)
    }

    override suspend fun myMenus(): List<DailyMenu> = emptyList()

    override suspend fun publish(body: MenuBody): DailyMenu = throw ownerOnly()

    override suspend fun revise(id: String, body: MenuBody): DailyMenu = throw ownerOnly()

    override suspend fun close(id: String, expectedVersion: Int?): CloseResponse = throw ownerOnly()

    private fun ownerOnly() = ApiException(
        ApiError(ApiException.FORBIDDEN, "Los datos de prueba solo simulan a un estudiante."),
        403,
    )
}
