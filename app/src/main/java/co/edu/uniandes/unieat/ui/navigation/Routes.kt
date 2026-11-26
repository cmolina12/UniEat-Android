package co.edu.uniandes.unieat.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

@Serializable object Login
@Serializable object Feed
@Serializable data class Detail(
    val menuId: String,
    val recommendedBy: String? = null,
    val recommendationReason: String? = null,
)
@Serializable object Recommend
@Serializable object Favorites
@Serializable object Publish
@Serializable object Performance
@Serializable object PublicationTiming
@Serializable object Profile

/** Sprint 4 (Samuel): the saved menus list, opened from Perfil. */
@Serializable object Saved

/** Sprint 4 (Camilo): Mis reportes, opened from Perfil, and one place of it. */
@Serializable object MyReports
@Serializable data class ReportedPlaceRoute(val establishmentId: String, val name: String, val area: String)

enum class Tab(val route: Any, val label: String, val icon: ImageVector) {
    FEED(Feed, "Hoy", Icons.Filled.Home),
    RECOMMEND(Recommend, "Elige por mí", Icons.Filled.Star),
    FAVORITES(Favorites, "Favoritos", Icons.Filled.Favorite),
    PUBLISH(Publish, "Publicar", Icons.Filled.AddCircle),
    PERFORMANCE(Performance, "Rendimiento", Icons.AutoMirrored.Filled.List),
    PROFILE(Profile, "Perfil", Icons.Filled.AccountCircle),
}
