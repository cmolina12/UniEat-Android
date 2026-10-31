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
import co.edu.uniandes.unieat.core.model.CompletenessGroup
import co.edu.uniandes.unieat.core.model.LocationCompletenessSnapshot
import co.edu.uniandes.unieat.core.model.LocationGuidanceSnapshot
import co.edu.uniandes.unieat.core.model.LocationSignals
import co.edu.uniandes.unieat.core.model.PerformanceSummary
import co.edu.uniandes.unieat.data.pilot.CachePilotGroup
import co.edu.uniandes.unieat.data.pilot.CachePilotReport
import co.edu.uniandes.unieat.data.pilot.PilotVariant
import co.edu.uniandes.unieat.data.telemetry.FeedLoadReport
import co.edu.uniandes.unieat.data.telemetry.SCOPE_THIS_DEVICE
import co.edu.uniandes.unieat.ui.common.SpanishPresentation
import co.edu.uniandes.unieat.ui.feed.FeedStatusCard
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.ChipRow
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import co.edu.uniandes.unieat.ui.theme.UniEatTheme
import kotlin.math.roundToInt

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
                state.savedAt?.let {
                    DemoNotice(
                        if (state.refreshing) "Actualizando… · mientras tanto, datos guardados a las ${SpanishPresentation.time(it)}"
                        else "Sin conexión · datos guardados a las ${SpanishPresentation.time(it)} · se actualizan al volver la señal",
                    )
                }
                SummaryCards(state.summary, state.ownEstablishmentsOnly)
                // BQ-01 is admin only (all devices)
                if (!state.ownEstablishmentsOnly) FeedLoadingBqCard(state.feedLoadReport)
                state.locationGuidance?.let { LocationGuidanceBqCard(it, state.ownEstablishmentsOnly) }
                state.locationCompleteness?.let { LocationCompletenessBqCard(it) }
                state.cachePilot?.let { CachePilotBqCard(it) }
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

/** BQ-10 (Camilo): complete places (pin + photo + entrance) vs incomplete ones. The backend does the math, this only shows it. */
@Composable
private fun LocationCompletenessBqCard(snapshot: LocationCompletenessSnapshot) {
    val complete = snapshot.groups.complete
    val incomplete = snapshot.groups.incomplete
    val rows: List<Pair<String, (CompletenessGroup) -> String>> = listOf(
        "Establecimientos" to { "${it.establishments}" },
        "Aperturas de ubicación" to { "${it.locationOpens}" },
        "Llegadas" to { "${it.arrivals}" },
        "No encontré el local" to { "${it.notFoundReports}" },
        "No lo encontraron" to { group -> group.notFoundShare?.let { "${(it * 100).roundToInt()} %" } ?: "—" },
    )
    SurfaceCard(Modifier.fillMaxWidth()) {
        Text("BQ-10 · Referencias completas vs incompletas", fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
        Text(
            "Todos los establecimientos · todas las plataformas · últimos ${snapshot.periodDays} días",
            style = MaterialTheme.typography.bodySmall,
        )
        // two value columns instead of three, so the long headers fit
        TableRow(listOf("", "Completos", "Incompletos"), header = true, labelWeight = BQ10_LABEL_WEIGHT)
        rows.forEach { (label, pick) -> TableRow(listOf(label, pick(complete), pick(incomplete)), labelWeight = BQ10_LABEL_WEIGHT) }
        Text(
            "Completo = pin, foto y descripción de la entrada. \"No lo encontraron\" = reportes de no encontrado " +
                "sobre el total de estudiantes que dijeron si llegaron o no. — = nadie ha respondido aún.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // restaurants: what each of their places is missing to be complete
        snapshot.ownEstablishments.forEach { own ->
            Text(
                if (own.missing.isEmpty()) "${own.name}: tiene todas las referencias."
                else "${own.name}: le falta ${own.missing.joinToString(", ") { missingLabel(it) }}.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private const val BQ10_LABEL_WEIGHT = 1.4f

/**
 * Samuel — BQ-13 (Type 1 + Type 3): cache-enabled vs online-only, compared only inside the same connection
 * type and the same "was there a saved copy?" condition. The backend (or this phone, as fallback) counts
 * and computes the percentiles; the card only lays them out side by side.
 */
@Composable
private fun CachePilotBqCard(report: CachePilotReport) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Text("BQ-13 · Piloto de caché del feed", fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
        val scope = if (report.scope == SCOPE_THIS_DEVICE) {
            "Solo este dispositivo (el servidor no respondió)"
        } else {
            "Todos los dispositivos"
        }
        Text(
            "$scope · últimos ${report.periodDays} días · ${report.sessions} visitas terminadas",
            style = MaterialTheme.typography.bodySmall,
        )
        if (report.groups.isEmpty()) {
            Text("Aún no hay visitas del piloto en este periodo. Los estudiantes se unen desde Perfil.")
            return@SurfaceCard
        }
        report.groups.groupBy { it.connectionType to it.cacheAvailable }.forEach { (condition, cells) ->
            val (connection, hadCopy) = condition
            val cached = cells.firstOrNull { it.variant == PilotVariant.CACHE_ENABLED.wireName }
            val online = cells.firstOrNull { it.variant == PilotVariant.ONLINE_ONLY.wireName }
            Text(
                "${connectionLabel(connection)} · " + if (hadCopy) "había copia guardada" else "sin copia guardada",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            TableRow(listOf("", "Con caché", "Solo en línea"), header = true, labelWeight = BQ10_LABEL_WEIGHT)
            val rows: List<Pair<String, (CachePilotGroup?) -> String>> = listOf(
                "Visitas" to { g -> g?.sessions?.toString() ?: "0" },
                "Vieron menús" to { g -> g?.withContent?.toString() ?: "—" },
                "Mediana hasta ver menús" to { g -> millis(g?.medianFirstContentMs) },
                "p95 hasta ver menús" to { g -> millis(g?.p95FirstContentMs) },
                "Terminaron sin elegir" to { g -> withoutSelection(g) },
            )
            rows.forEach { (label, pick) ->
                TableRow(listOf(label, pick(cached), pick(online)), labelWeight = BQ10_LABEL_WEIGHT)
            }
        }
        Text(
            "Tiempo = desde que se pide el feed hasta ver menús (de la copia o del servidor). " +
                "\"Sin elegir\" = visitas que terminaron sin tocar «Elegir este menú». El porcentaje aparece con " +
                "${report.minSessionsForShare} visitas o más por grupo; antes se muestra la cuenta. " +
                "Es una comparación descriptiva, no una prueba estadística.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun millis(value: Long?): String = value?.let { "$it ms" } ?: "—"

/** "3/8 · 38 %" once there are enough visits, just "1/2" before that. */
private fun withoutSelection(group: CachePilotGroup?): String {
    if (group == null) return "—"
    val count = "${group.withoutSelection}/${group.sessions}"
    return group.withoutSelectionShare?.let { "$count · ${(it * 100).roundToInt()} %" } ?: count
}

private fun connectionLabel(type: String): String = when (type) {
    "wifi" -> "Wi-Fi"
    "cellular" -> "Datos móviles"
    "ethernet" -> "Ethernet"
    "offline" -> "Sin red"
    else -> "Otra conexión"
}

private fun missingLabel(reference: String): String = when (reference) {
    "pin" -> "el pin en el mapa"
    "photo" -> "la foto"
    "entrance" -> "la descripción de la entrada"
    else -> reference
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false, labelWeight: Float = 2.2f) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEachIndexed { i, cell ->
            Text(
                cell,
                // gap so "Completos" and "Incompletos" don't touch
                modifier = Modifier.weight(if (i == 0) labelWeight else 1f).padding(start = if (i == 0) 0.dp else 6.dp),
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
