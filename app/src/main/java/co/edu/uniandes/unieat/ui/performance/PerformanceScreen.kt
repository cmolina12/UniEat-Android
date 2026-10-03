package co.edu.uniandes.unieat.ui.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.PerformanceSummary
import co.edu.uniandes.unieat.ui.feed.FeedStatusCard
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.ChipRow
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import co.edu.uniandes.unieat.ui.theme.UniEatTheme

/** BQ dashboard: the pipeline metrics from GET /performance, all visible on one screen. */
@Composable
fun PerformanceScreen(
    onSessionExpired: () -> Unit,
    viewModel: PerformanceViewModel = viewModel(factory = PerformanceViewModel.factory()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()
    val container = (LocalContext.current.applicationContext as UniEatApplication).container

    // Expired session: back to the login, like the feed does.
    LaunchedEffect(state) {
        if (state is PerformanceUiState.SessionExpired) onSessionExpired()
    }

    PerformanceContent(
        state = state,
        days = days,
        isDemo = container.usesFakeData,
        onSelectDays = viewModel::selectDays,
        onRetry = viewModel::load,
    )
}

@Composable
private fun PerformanceContent(
    state: PerformanceUiState,
    days: Int,
    isDemo: Boolean,
    onSelectDays: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BrandHeader("Rendimiento")
        if (isDemo) DemoNotice("Datos de prueba (seed.sql) · sin conexión al servidor")

        Text("Métricas del pipeline", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
        ChipRow(
            options = listOf(7, 28),
            isSelected = { it == days },
            label = { "Últimos $it días" },
            onSelect = onSelectDays,
        )

        when (state) {
            PerformanceUiState.Loading -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                CircularProgressIndicator(color = Palette.Ink)
            }
            is PerformanceUiState.Restricted -> FeedStatusCard(
                title = "Acceso restringido",
                body = state.message,
            )
            PerformanceUiState.SessionExpired -> FeedStatusCard(
                title = "Tu sesión terminó",
                body = "Inicia sesión de nuevo para ver las métricas.",
            )
            is PerformanceUiState.Error -> FeedStatusCard(
                title = "No pudimos cargar las métricas",
                body = state.message,
            ) { SolidButton("Reintentar", onClick = onRetry, icon = Icons.Filled.Refresh, color = Palette.Yellow) }
            is PerformanceUiState.Content -> SummaryCards(state.summary)
        }
    }
}

@Composable
private fun SummaryCards(summary: PerformanceSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (summary.insufficientData) {
            FeedStatusCard(
                title = "Datos insuficientes",
                body = "El periodo aún no tiene suficientes eventos para métricas confiables. " +
                    "Las cifras se muestran, pero tómalas con cautela.",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Impresiones", summary.impressions, Modifier.weight(1f))
            MetricCard("Aperturas", summary.detailOpens, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Selecciones", summary.selections, Modifier.weight(1f))
            MetricCard("Llegadas", summary.reportedArrivals, Modifier.weight(1f))
        }
        Text(
            "Periodo de ${summary.periodDays} días · ${summary.sampleSize} eventos en total",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One number with its label; the backend computed the number, the card only shows it. */
@Composable
private fun MetricCard(label: String, value: Int, modifier: Modifier = Modifier) {
    SurfaceCard(modifier) {
        Text("$value", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Palette.Ink)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F0E6, heightDp = 700)
@Composable
private fun PerformancePreview() = UniEatTheme {
    PerformanceContent(
        state = PerformanceUiState.Content(
            PerformanceSummary(
                periodDays = 7, impressions = 48, detailOpens = 21, selections = 9,
                reportedArrivals = 5, sampleSize = 48, insufficientData = false,
            ),
        ),
        days = 7,
        isDemo = true,
        onSelectDays = {},
        onRetry = {},
    )
}
