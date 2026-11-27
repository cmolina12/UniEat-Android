package co.edu.uniandes.unieat.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

data class FavoriteRestaurant(
    val establishmentId: String,
    val name: String,
    val area: String,
    val addedAt: Long,
)

class FavoriteRestaurants {
    private val saved = MutableStateFlow<List<FavoriteRestaurant>>(emptyList())

    suspend fun all(): List<FavoriteRestaurant> = saved.value.sortedByDescending { it.addedAt }

    fun isFavorite(establishmentId: String): Flow<Boolean> =
        saved.map { list -> list.any { it.establishmentId == establishmentId } }

    suspend fun insert(favorite: FavoriteRestaurant) =
        saved.update { list -> list.filterNot { it.establishmentId == favorite.establishmentId } + favorite }

    suspend fun delete(establishmentId: String) =
        saved.update { list -> list.filterNot { it.establishmentId == establishmentId } }

    suspend fun deleteAll() = saved.update { emptyList() }
}
