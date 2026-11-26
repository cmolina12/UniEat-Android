package co.edu.uniandes.unieat.ui.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.edu.uniandes.unieat.core.model.PublicationTimingSlot
import co.edu.uniandes.unieat.ui.common.SpanishPresentation
import co.edu.uniandes.unieat.ui.feed.FeedStatusCard
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.ChipRow
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import kotlin.math.roundToInt

@Composable
fun PublicationTimingScreen(
    viewModel: PublicationTimingViewModel,
    approvedIds: Set<String>,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.sessionExpired) {
        if (state.sessionExpired) onSessionExpired()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack) { Text("Volver a Rendimiento") }
        BrandHeader("Cuándo publicar")
        val snapshot = state.snapshot
        if (snapshot != null) {
            Text("Aperturas del detalle durante la primera hora · últimos ${snapshot.periodDays} días · hora de Bogotá")
            if (state.showingCopy) {
                DemoNotice("Copia del ${SpanishPresentation.dateAndTime(snapshot.asOf)}")
            } else {
                Text("Actualizado el ${SpanishPresentation.dateAndTime(snapshot.asOf)}", style = MaterialTheme.typography.bodySmall)
            }
            val groups = snapshot.groups.filter { it.id in approvedIds }
            val selected = groups.firstOrNull { it.id == selectedId } ?: groups.firstOrNull()
            if (selected != null) {
                if (groups.size > 1) {
                    ChipRow(options = groups, isSelected = { it.id == selected.id }, label = { it.name },
                        onSelect = { selectedId = it.id })
                } else {
                    Text(selected.name, style = MaterialTheme.typography.titleMedium)
                }
                selected.slots.forEach { TimingSlotCard(it) }
                Text("Se necesitan al menos ${snapshot.minimumSessions} sesiones expuestas en una franja.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                FeedStatusCard(title = "Sin locales en esta consulta", body = "Solo aparecen tus establecimientos aprobados.")
            }
        }

        when {
            state.error != null -> FeedStatusCard(title = "No se pudo actualizar", body = state.error.orEmpty()) {
                OutlinedButton(onClick = viewModel::reload, enabled = state.online) { Text("Reintentar") }
            }
            state.refreshing -> CircularProgressIndicator()
            !state.online && snapshot == null -> FeedStatusCard(title = "Sin consulta guardada",
                body = "Abre esta pantalla con conexión para guardar la primera copia.")
            state.online -> OutlinedButton(onClick = viewModel::reload) { Text("Actualizar") }
        }
    }
}

@Composable
private fun TimingSlotCard(slot: PublicationTimingSlot) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Text("${slot.startHour.toString().padStart(2, '0')}:00–${slot.endHour.toString().padStart(2, '0')}:00",
            style = MaterialTheme.typography.titleMedium)
        val rate = slot.detailOpenRate
        if (rate == null) {
            Text("Muestra insuficiente")
        } else {
            Text("${(rate * 100).roundToInt()} %")
            LinearProgressIndicator(progress = { rate.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        Text("Publicaciones: ${slot.publications} · Exposiciones: ${slot.exposedPairs}", style = MaterialTheme.typography.bodySmall)
    }
}
