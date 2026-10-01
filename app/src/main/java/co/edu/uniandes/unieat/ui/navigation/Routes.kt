package co.edu.uniandes.unieat.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

// Type-safe Navigation Compose destinations.

@Serializable object Login
@Serializable object Feed
@Serializable data class Detail(val menuId: String)
@Serializable object Recommend
@Serializable object Publish
@Serializable object Performance
@Serializable object Profile

/** Bottom bar tabs, labels as in iOS MainTabsView. Role-based hiding comes with the auth feature. */
enum class Tab(val route: Any, val label: String, val icon: ImageVector) {
    FEED(Feed, "Hoy", Icons.Filled.Home),
    RECOMMEND(Recommend, "Elige por mí", Icons.Filled.Star),
    PUBLISH(Publish, "Publicar", Icons.Filled.AddCircle),
    PERFORMANCE(Performance, "Rendimiento", Icons.AutoMirrored.Filled.List),
    PROFILE(Profile, "Perfil", Icons.Filled.AccountCircle),
}
