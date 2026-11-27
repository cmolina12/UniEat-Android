package co.edu.uniandes.unieat.data.local

import android.content.Context

data class Favorite(val id: String, val name: String, val area: String)

class Favorites(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun all(): List<Favorite> {
        val list = mutableListOf<Favorite>()
        for (id in prefs.all.keys) {
            val parts = prefs.getString(id, "|").orEmpty().split("|")
            list.add(Favorite(id, parts[0], parts[1]))
        }
        return list
    }

    fun isFavorite(id: String): Boolean {
        return prefs.contains(id)
    }

    fun add(favorite: Favorite) {
        prefs.edit().putString(favorite.id, favorite.name + "|" + favorite.area).apply()
    }

    fun remove(id: String) {
        prefs.edit().remove(id).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
