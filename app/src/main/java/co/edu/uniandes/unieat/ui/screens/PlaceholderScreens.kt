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

// The Feed ("Hoy") and "Elige por mí" live in ui/feed; "Rendimiento" lives in ui/performance.

@Composable
fun PublishScreen() = Placeholder("Publicar", "Crear o editar el menú del día (POST/PUT /menus).")

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
private fun PlaceholderPreview() = UniEatTheme { PublishScreen() }
