package co.edu.uniandes.unieat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import co.edu.uniandes.unieat.ui.theme.UniEatTheme

// Placeholder destinations. Each one moves to its own feature package with a ViewModel when built.

private const val DEMO_MENU_ID = "10000000-0000-4000-8000-000000000002"

@Composable
fun LoginScreen(onSignedIn: () -> Unit) = Placeholder("Ingresar", "Inicio de sesión con Supabase Auth.") {
    SolidButton("Entrar (demo)", onClick = onSignedIn)
}

@Composable
fun FeedScreen(onOpenMenu: (String) -> Unit) = Placeholder("Hoy", "Menús vigentes ordenados con rank-v1 (GET /feed).") {
    SolidButton("Ver detalle de ejemplo", onClick = { onOpenMenu(DEMO_MENU_ID) }, color = Palette.Cyan)
}

@Composable
fun DetailScreen(menuId: String, onBack: () -> Unit) = Placeholder("Detalle", "GET /menus/$menuId") {
    SolidButton("Volver", onClick = onBack, color = Palette.Yellow)
}

@Composable
fun RecommendScreen(onOpenMenu: (String) -> Unit) =
    Placeholder("Elige por mí", "Primer menú del feed según tus filtros.") {
        SolidButton("Ver recomendación", onClick = { onOpenMenu(DEMO_MENU_ID) }, color = Palette.Green)
    }

@Composable
fun PublishScreen() = Placeholder("Publicar", "Crear o editar el menú del día (POST/PUT /menus).")

@Composable
fun PerformanceScreen() = Placeholder("Rendimiento", "Impresiones, aperturas y selecciones (GET /performance).")

@Composable
fun ProfileScreen(onSignOut: () -> Unit) = Placeholder("Mi perfil", "Datos de GET /me y preferencias.") {
    SolidButton("Cerrar sesión", onClick = onSignOut, color = Palette.Yellow)
}

@Composable
private fun Placeholder(title: String, description: String, actions: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BrandHeader(title)
        DemoNotice("Pantalla en construcción")
        SurfaceCard {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        actions()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F0E6)
@Composable
private fun PlaceholderPreview() = UniEatTheme { FeedScreen(onOpenMenu = {}) }
