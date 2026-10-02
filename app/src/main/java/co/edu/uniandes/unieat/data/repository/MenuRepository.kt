package co.edu.uniandes.unieat.data.repository

import co.edu.uniandes.unieat.core.model.CloseResponse
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.FeedResponse
import co.edu.uniandes.unieat.core.model.MenuBody
import co.edu.uniandes.unieat.core.model.MenuDetailResponse

/**
 * Menu data source for ViewModels. Implementations throw
 * [co.edu.uniandes.unieat.data.remote.ApiException] on failure.
 */
interface MenuRepository {
    /** GET /feed — ranked by rank-v1; the first item is "Elige por mí". */
    suspend fun feed(filters: FeedFilters): FeedResponse

    /** GET /menus/:id — current version; 410 GONE for students if closed or expired. */
    suspend fun menu(id: String): MenuDetailResponse

    /** GET /menus/mine — all publications of the owner's establishments. */
    suspend fun myMenus(): List<DailyMenu>

    /** POST /menus — creates version 1. */
    suspend fun publish(body: MenuBody): DailyMenu

    /** PUT /menus/:id — creates version n+1; 409 VERSION_CONFLICT if [MenuBody.expectedVersion] is stale. */
    suspend fun revise(id: String, body: MenuBody): DailyMenu

    /** POST /menus/:id/close — idempotent. */
    suspend fun close(id: String, expectedVersion: Int?): CloseResponse
}
