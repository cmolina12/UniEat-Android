package co.edu.uniandes.unieat.data.repository

import co.edu.uniandes.unieat.core.model.CloseBody
import co.edu.uniandes.unieat.core.model.CloseResponse
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.FeedResponse
import co.edu.uniandes.unieat.core.model.MenuBody
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import co.edu.uniandes.unieat.core.model.MenuResponse
import co.edu.uniandes.unieat.core.model.MenusResponse
import co.edu.uniandes.unieat.data.remote.ApiClient
import io.ktor.http.HttpMethod

/** [MenuRepository] backed by api-v1 through [ApiClient]. */
class RemoteMenuRepository(private val api: ApiClient) : MenuRepository {

    override suspend fun feed(filters: FeedFilters, origin: String?): FeedResponse =
        api.get("feed", filters.toQuery() + ("origin" to origin))

    override suspend fun menu(id: String): MenuDetailResponse =
        api.get("menus/$id")

    override suspend fun myMenus(): List<DailyMenu> =
        api.get<MenusResponse>("menus/mine").menus

    override suspend fun publish(body: MenuBody): DailyMenu =
        api.send<MenuResponse, MenuBody>(HttpMethod.Post, "menus", body).menu

    override suspend fun revise(id: String, body: MenuBody): DailyMenu =
        api.send<MenuResponse, MenuBody>(HttpMethod.Put, "menus/$id", body).menu

    override suspend fun close(id: String, expectedVersion: Int?): CloseResponse =
        api.send(HttpMethod.Post, "menus/$id/close", CloseBody(expectedVersion))

    private fun FeedFilters.toQuery(): Map<String, Any?> = mapOf(
        "budgetCop" to budgetCop,
        "availableMinutes" to availableMinutes,
        "diet" to diet,
        "area" to area,
        "paymentMethod" to paymentMethod,
    )
}
