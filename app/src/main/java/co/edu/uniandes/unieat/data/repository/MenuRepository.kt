package co.edu.uniandes.unieat.data.repository

import co.edu.uniandes.unieat.core.model.CloseResponse
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.FeedResponse
import co.edu.uniandes.unieat.core.model.MenuBody
import co.edu.uniandes.unieat.core.model.MenuDetailResponse

interface MenuRepository {
    /** GET /feed — ranked by rank-v1; the first item is "Elige por mí". */
    suspend fun feed(filters: FeedFilters): FeedResponse

    /** [automatic] marks reloads the app makes on its own, so they do not count as detail views (BQ-04). */
    suspend fun menu(id: String, automatic: Boolean = false): MenuDetailResponse

    suspend fun myMenus(): List<DailyMenu>

    suspend fun publish(body: MenuBody): DailyMenu

    suspend fun revise(id: String, body: MenuBody): DailyMenu

    suspend fun close(id: String, expectedVersion: Int?): CloseResponse
}
