package co.edu.uniandes.unieat.data.fake

import co.edu.uniandes.unieat.core.decision.Coordinate
import co.edu.uniandes.unieat.core.decision.proximity
import co.edu.uniandes.unieat.core.model.CloseResponse
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.FeedResponse
import co.edu.uniandes.unieat.core.model.MenuBody
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import co.edu.uniandes.unieat.core.model.TravelEvidence
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

    override suspend fun feed(filters: FeedFilters, origin: String?): FeedResponse {
        delay(latencyMillis)
        val now = clock()
        val menus = FakeBackend.menus(now)
            .filter { it.closedAt == null && it.isActive(now) && it.matches(filters) }
            .withTravelFrom(origin)
        return FeedResponse(serverNow = now, fetchedAt = now, menus = menus, resultCount = menus.size)
    }

    /** Like rank-v1 with `origin`: walking minutes per menu with a pin, closest first. */
    private fun List<DailyMenu>.withTravelFrom(origin: String?): List<DailyMenu> {
        val user = origin.toCoordinateOrNull() ?: return this
        return map { menu ->
            val pin = menu.latitude?.let { lat -> menu.longitude?.let { lon -> Coordinate(lat, lon) } }
            if (pin == null) {
                menu.copy(travelMinutes = null, travelEvidence = TravelEvidence.UNKNOWN)
            } else {
                menu.copy(
                    travelMinutes = proximity(user, null, pin).walkingMinutes,
                    travelEvidence = TravelEvidence.APPROXIMATE,
                )
            }
        }.sortedBy { it.travelMinutes ?: Int.MAX_VALUE }
    }

    private fun String?.toCoordinateOrNull(): Coordinate? {
        val parts = this?.split(",") ?: return null
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        return Coordinate(lat, lon)
    }

    /** Same filtering api-v1 applies server-side, so the demo behaves like the real feed. */
    private fun DailyMenu.matches(f: FeedFilters): Boolean = when {
        f.budgetCop != null && lowestPriceCop > f.budgetCop -> false
        f.area != null && area != f.area -> false
        f.diet != null && items.none { f.diet in it.dietaryTags } -> false
        f.paymentMethod != null && f.paymentMethod !in paymentMethods -> false
        else -> true
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
