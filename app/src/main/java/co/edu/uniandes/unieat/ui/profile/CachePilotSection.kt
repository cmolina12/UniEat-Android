package co.edu.uniandes.unieat.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import co.edu.uniandes.unieat.data.pilot.PilotMembership
import co.edu.uniandes.unieat.data.pilot.PilotStatus
import co.edu.uniandes.unieat.data.pilot.PilotVariant
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.Sticker
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the BQ-13 card in Perfil shows. */
data class CachePilotUiState(
    val loading: Boolean = true,
    val status: PilotStatus = PilotStatus(signedIn = false, joined = false, variant = null),
    /** Joining or leaving in progress: the switch is disabled meanwhile, so a double tap does nothing. */
    val working: Boolean = false,
)

/**
 * Samuel — BQ-13 consent. The pilot is opt-in: nobody is placed in a group without turning this on, and
 * turning it off restores the normal cache immediately. Everything here is local (DataStore + Room), so it
 * works the same with or without signal: joining needs no server round trip.
 */
class CachePilotViewModel(private val pilot: PilotMembership) : ViewModel() {
    private val _state = MutableStateFlow(CachePilotUiState())
    val state: StateFlow<CachePilotUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads the status: the signed-in account may have changed since the card was created. */
    fun refresh() {
        viewModelScope.launch { _state.value = CachePilotUiState(loading = false, status = pilot.status()) }
    }

    fun setJoined(join: Boolean) {
        if (_state.value.working) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            if (join) pilot.join() else pilot.leave()
            _state.value = CachePilotUiState(loading = false, status = pilot.status())
        }
    }
}

/** Card under the role sections of Perfil, for every role. */
@Composable
fun CachePilotSection(vm: CachePilotViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.refresh() }
    val status = state.status

    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Piloto de caché (BQ-13)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Palette.Ink,
            )
            Text(
                "Estamos comparando dos formas de cargar \"Hoy\": mostrando primero los menús guardados en el " +
                    "teléfono o esperando siempre al servidor. Si te unes, la app te asigna uno de los dos grupos " +
                    "y siempre te deja en el mismo.",
                fontSize = 14.sp,
            )
            Text(
                "Qué se registra: cuánto tarda el feed en mostrar menús, el tipo de conexión, si había una copia " +
                    "guardada y si terminaste eligiendo un menú. No se registra tu ubicación ni qué menú elegiste.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Participar en el piloto", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = status.joined,
                    onCheckedChange = vm::setJoined,
                    enabled = status.signedIn && !state.working && !state.loading,
                )
            }
            when {
                !status.signedIn && !state.loading -> Text("Inicia sesión para participar.", fontSize = 13.sp)
                status.variant == PilotVariant.CACHE_ENABLED -> {
                    Sticker("Tu grupo: con copia guardada", color = Palette.Green)
                    Text("Ves los menús guardados mientras llegan los nuevos, también sin señal.", fontSize = 13.sp)
                }
                status.variant == PilotVariant.ONLINE_ONLY -> {
                    Sticker("Tu grupo: solo en línea", color = Palette.Cyan)
                    Text(
                        "\"Hoy\" espera siempre al servidor; sin señal no verás menús guardados. " +
                            "Si lo necesitas, sal del piloto y vuelven de inmediato.",
                        fontSize = 13.sp,
                    )
                }
            }
            if (status.joined) {
                Text(
                    "Al salir borramos las visitas que aún no se habían enviado.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
