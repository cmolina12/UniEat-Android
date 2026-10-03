package co.edu.uniandes.unieat.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.ui.common.SpanishPresentation
import co.edu.uniandes.unieat.ui.common.rememberServerNow
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.ChipRow
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import co.edu.uniandes.unieat.ui.theme.cop
import androidx.compose.ui.platform.LocalContext

/**
 * Smart feature "Elige por mí": shows the backend's best-ranked menu with its explanation.
 * Reuses the same FeedViewModel class (own instance per tab, filters shared through AppContainer).
 */
@Composable
fun RecommendScreen(
    onOpenMenu: (menuId: String, criterion: String, reason: String) -> Unit,
    onSessionExpired: () -> Unit,
    viewModel: FeedViewModel = viewModel(factory = FeedViewModel.factory()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val index by viewModel.recommendationIndex.collectAsStateWithLifecycle()
    val strategy by viewModel.strategy.collectAsStateWithLifecycle()
    val container = (LocalContext.current.applicationContext as UniEatApplication).container

    LaunchedEffect(state) {
        if (state is FeedUiState.SessionExpired) onSessionExpired()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BrandHeader("Elige por mí")
        if (container.usesFakeData) DemoNotice("Datos de prueba (seed.sql) · sin conexión al servidor")

        when (state) {
            FeedUiState.Loading -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                CircularProgressIndicator(color = Palette.Ink)
            }
            FeedUiState.SessionExpired ->
                FeedStatusCard(title = "Tu sesión terminó", body = "Inicia sesión de nuevo para ver la recomendación.")
            is FeedUiState.Error -> FeedStatusCard(
                title = "No pudimos cargar la recomendación",
                body = (state as FeedUiState.Error).message,
            ) { SolidButton("Reintentar", onClick = viewModel::load, icon = Icons.Filled.Refresh, color = Palette.Yellow) }
            is FeedUiState.Content -> {
                val content = state as FeedUiState.Content
                // Strategy pattern: the active criterion picks among the backend's options
                val serverNow = rememberServerNow(content.clockOffset)
                val recommended = strategy.pick(content.menus, index, serverNow)
                if (recommended == null) {
                    EmptyFeedCard()
                } else {
                    FiltersSummaryCard(filters, content.menus.size)
                    Text("Criterio", style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
                    ChipRow(
                        options = listOf(BestRankedStrategy, CheapestStrategy, FastestWaitStrategy),
                        isSelected = { it == strategy },
                        label = { it.label },
                        onSelect = viewModel::selectStrategy,
                    )
                    Text("Hoy prueba aquí", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
                    Text(
                        "La opción cumple tus filtros declarados. Revisa la información estimada antes de ir.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // The card already shows the backend's explanation: the "why" of the ranking.
                    MenuCard(recommended, serverNow, onClick = { onOpenMenu(recommended.id, strategy.label, recommended.explanation) })
                    SolidButton("Elegir otra opción", onClick = viewModel::nextRecommendation, color = Palette.Cyan)
                    SolidButton(
                        "Ver publicación completa",
                        onClick = { onOpenMenu(recommended.id, strategy.label, recommended.explanation) },
                        icon = Icons.Filled.Star,
                        color = Palette.Green,
                    )
                }
            }
        }
    }
}

/** What the recommendation obeyed: the active filters and how many options matched. */
@Composable
private fun FiltersSummaryCard(filters: FeedFilters, optionCount: Int) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Filtros aplicados", style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(
                    filters.budgetCop?.let { "Presupuesto: hasta ${it.cop}" },
                    filters.availableMinutes?.let { "Tiempo: $it min" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Zona: ${filters.area ?: "Todas"} · Dieta: ${SpanishPresentation.diet(filters.diet)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "$optionCount opción(es) compatible(s)",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
