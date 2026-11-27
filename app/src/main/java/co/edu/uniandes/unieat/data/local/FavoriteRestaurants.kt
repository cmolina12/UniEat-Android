package co.edu.uniandes.unieat.data.local

import android.content.Context

data class FavoriteRestaurant(val establishmentId: String, val name: String, val area: String)

class FavoriteRestaurants(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun all(): List<FavoriteRestaurant> {
        val list = mutableListOf<FavoriteRestaurant>()
        for (id in prefs.all.keys) {
            val parts = prefs.getString(id, "|").orEmpty().split("|")
            list.add(FavoriteRestaurant(id, parts[0], parts[1]))
        }
        return list
    }

    fun isFavorite(establishmentId: String): Boolean {
        return prefs.contains(establishmentId)
    }

    fun add(favorite: FavoriteRestaurant) {
        prefs.edit().putString(favorite.establishmentId, favorite.name + "|" + favorite.area).apply()
    }

    fun remove(establishmentId: String) {
        prefs.edit().remove(establishmentId).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
