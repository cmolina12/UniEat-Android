package co.edu.uniandes.unieat.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.FoodArtwork
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.Sticker
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import co.edu.uniandes.unieat.ui.theme.cop

@Composable
fun FavoritesScreen(
    onOpenMenu: (menuId: String) -> Unit,
    viewModel: FavoritesViewModel = viewModel(factory = FavoritesViewModel.factory()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose {}
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BrandHeader("Mis favoritos")
        when (val current = state) {
            FavoritesUiState.Loading -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                CircularProgressIndicator(color = Palette.Ink)
            }
            is FavoritesUiState.Content -> {
                if (current.error != null) DemoNotice(current.error)
                if (current.rows.isEmpty()) {
                    Text(
                        "Aún no tienes restaurantes favoritos. Toca el corazón en el detalle de un menú para guardarlo aquí.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                current.rows.forEach { row -> FavoriteCard(row, current.error != null, onOpenMenu) }
            }
        }
    }
}

@Composable
private fun FavoriteCard(row: FavoriteRow, menusUnknown: Boolean, onOpenMenu: (String) -> Unit) {
    val menu = row.menu
    SurfaceCard(
        Modifier
            .fillMaxWidth()
            .then(if (menu != null) Modifier.clickable { onOpenMenu(menu.id) } else Modifier),
    ) {
        if (menu != null) FoodArtwork(row.favorite.name, menu.photoUrl, height = 120.dp)
        Text(row.favorite.name, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
        Sticker(row.favorite.area, color = Palette.Cyan, icon = Icons.Filled.Place)
        Text(
            when {
                menu != null -> "Hoy: ${menu.title} · desde ${menu.lowestPriceCop.cop}"
                menusUnknown -> "No sabemos si publicó menú hoy"
                else -> "Hoy no ha publicado menú"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
