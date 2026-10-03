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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.LocationGuidanceSnapshot
import co.edu.uniandes.unieat.core.model.LocationSignals
import co.edu.uniandes.unieat.core.model.PerformanceSummary
import co.edu.uniandes.unieat.data.telemetry.FeedLoadReport
import co.edu.uniandes.unieat.data.telemetry.SCOPE_THIS_DEVICE
import co.edu.uniandes.unieat.ui.feed.FeedStatusCard
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.ChipRow
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import co.edu.uniandes.unieat.ui.theme.UniEatTheme

@Composable
fun PerformanceScreen(
    role: String?,
    userId: String?,
    onSessionExpired: () -> Unit,
    viewModel: PerformanceViewModel = viewModel(
        key = "performance:$userId:$role",
        factory = PerformanceViewModel.factory(role),
    ),
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
            is PerformanceUiState.Content -> {
                SummaryCards(state.summary, state.ownEstablishmentsOnly)
                FeedLoadingBqCard(state.feedLoadReport)
                state.locationGuidance?.let { LocationGuidanceBqCard(it, state.ownEstablishmentsOnly) }
            }
        }
    }
}

@Composable
private fun SummaryCards(summary: PerformanceSummary, ownEstablishmentsOnly: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val clients = when (summary.platform) {
            "all" -> "iOS y Android"
            "android" -> "solo Android"
            else -> "solo eventos de la app iOS"
        }
        Text(
            (if (ownEstablishmentsOnly) "Tus establecimientos" else "Todos los establecimientos") + " · $clients",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            "Periodo de ${summary.periodDays} días · ${summary.sampleSize} sesiones distintas con eventos",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Samuel — BQ-01: seven-day technical diagnostic from persisted feed-load telemetry. */
@Composable
private fun FeedLoadingBqCard(report: FeedLoadReport?) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Text("BQ-01 · Rendimiento de carga del feed", fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
        if (report == null || (report.attempts == 0 && report.abandoned == 0)) {
            Text("Aún no hay cargas registradas en los últimos 7 días.")
            return@SurfaceCard
        }
        val scope = if (report.scope == SCOPE_THIS_DEVICE) {
            "Solo este dispositivo (el servidor no respondió)"
        } else {
            "Todos los dispositivos"
        }
        Text("$scope · últimos ${report.periodDays} días", style = MaterialTheme.typography.bodySmall)
        Text("${report.attempts} cargas completadas · agrupadas por conexión, dispositivo, Android y hora.")
        if (report.abandoned > 0) {
            Text(
                "${report.abandoned} cargas abandonadas (el usuario salió antes de ver el feed); no cuentan como fallos.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        report.groups.take(5).forEach { group ->
            val failure = (group.failureRate * 100).toInt()
            val p95 = group.p95RequestToRenderMs?.let { "$it ms" } ?: "sin renders suficientes"
            Text(
                "${group.connectionType} · ${group.deviceModel} · ${group.osVersion} · ${group.hour}:00 — " +
                    "fallos $failure% (${group.failures}/${group.attempts}), p95 $p95",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (report.groups.size > 5) {
            Text("Se muestran los 5 grupos con mayor tasa de fallo/latencia.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LocationGuidanceBqCard(snapshot: LocationGuidanceSnapshot, ownEstablishmentsOnly: Boolean) {
    val columns = listOf(snapshot.ios, snapshot.android, snapshot.unknown)
    val rows: List<Pair<String, (LocationSignals) -> Int>> = listOf(
        "Aperturas de Maps" to { it.locationOpens },
        "Llegadas reportadas" to { it.reportedArrivals },
        "Reportes pendientes" to { it.locationReports.pending },
        "Reportes confirmados" to { it.locationReports.confirmed },
        "Reportes descartados" to { it.locationReports.dismissed },
    )
    SurfaceCard(Modifier.fillMaxWidth()) {
        Text("BQ-05 · Orientación de ubicación", fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
        val scope = if (ownEstablishmentsOnly) "Tus establecimientos" else "Todos los establecimientos"
        Text("$scope · todas las plataformas · últimos ${snapshot.periodDays} días", style = MaterialTheme.typography.bodySmall)
        TableRow(listOf("", "iOS", "Android", "Sin dato"), header = true)
        rows.forEach { (label, pick) -> TableRow(listOf(label) + columns.map { pick(it).toString() }) }
        Text(
            "Sin dato: registros enviados sin la marca de plataforma (por ejemplo, versiones viejas de la app).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val c = snapshot.coverage
        Text(
            "Referencias en ${c.establishments} " + (if (c.establishments == 1) "establecimiento" else "establecimientos") +
                " (hoy, sin importar el periodo)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )
        TableRow(listOf("", "Tienen", "Les falta"), header = true)
        TableRow(listOf("Coordenadas", "${c.withCoordinates}", "${c.withoutCoordinates}"))
        TableRow(listOf("Indicación de entrada", "${c.withEntranceDescription}", "${c.withoutEntranceDescription}"))
        TableRow(listOf("Foto", "${c.withPhoto}", "${c.withoutPhoto}"))
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEachIndexed { i, cell ->
            Text(
                cell,
                modifier = Modifier.weight(if (i == 0) 2.2f else 1f),
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
            )
        }
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
