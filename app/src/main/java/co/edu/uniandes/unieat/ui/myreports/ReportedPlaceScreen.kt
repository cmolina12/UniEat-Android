package co.edu.uniandes.unieat.ui.myreports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.edu.uniandes.unieat.core.model.MyReport
import co.edu.uniandes.unieat.ui.common.SpanishPresentation
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.Sticker
import co.edu.uniandes.unieat.ui.theme.SurfaceCard

/** Detail of one place in Mis reportes. [name] and [area] come from the list so the header shows at once. */
@Composable
fun ReportedPlaceScreen(
    establishmentId: String,
    name: String,
    area: String,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
    vm: ReportedPlaceViewModel = viewModel(
        key = establishmentId,
        factory = ReportedPlaceViewModel.factory(establishmentId),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.sessionExpired) { if (state.sessionExpired) onSessionExpired() }
    val place = state.place

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader(place?.name ?: name, onBack, subtitle = place?.area ?: area) }
        if (place != null) {
            item {
                Text(
                    "${place.total} reportes · ${place.confirmed} confirmados · ${place.pending} en revisión",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        state.error?.let { message ->
            item {
                SurfaceCard(Modifier.fillMaxWidth()) {
                    Text(message, color = Palette.Ink, fontWeight = FontWeight.Bold)
                    SolidButton("Reintentar", onClick = vm::load, icon = Icons.Filled.Refresh, color = Palette.Yellow)
                }
            }
        }
        if (state.loading && state.reports.isEmpty()) item { Loading("Cargando reportes…") }
        items(state.reports, key = { it.id }) { ReportCard(it) }
    }
}

@Composable
private fun ReportCard(report: MyReport) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(report.kindIcon, contentDescription = null, tint = Palette.Ink)
                Text(report.kindTitle, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
            }
            Text("${report.menuTitle} · versión ${report.version}", style = MaterialTheme.typography.bodyMedium)
            report.observedWaitMinutes?.let { Text("Fila: $it min", fontSize = 13.sp) }
            if (report.note.isNotBlank()) Text("“${report.note}”", fontSize = 13.sp, fontStyle = FontStyle.Italic)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Sticker(report.statusTitle, color = report.statusColor) }
                Text(
                    SpanishPresentation.dateAndTime(report.observedAt),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            report.resolvedAt?.let {
                Text(
                    "Resuelto: ${SpanishPresentation.dateAndTime(it)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
