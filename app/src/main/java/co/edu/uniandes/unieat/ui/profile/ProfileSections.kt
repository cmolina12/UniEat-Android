package co.edu.uniandes.unieat.ui.profile

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.edu.uniandes.unieat.ui.common.SpanishPresentation
import co.edu.uniandes.unieat.ui.detail.OsmMap
import co.edu.uniandes.unieat.ui.theme.ChipRow
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import java.time.Instant

/** Student or restaurant: state of their establishments and the request form. */
@Composable
fun EstablishmentsSection(vm: EstablishmentsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title("Mis establecimientos")
            if (state.offline) {
                DemoNotice(
                    savedNotice("estado guardado", state.savedAt) +
                        " · tu solicitud se guarda como borrador; enviarla y actualizar se activan al volver la señal",
                )
            }
            if (state.memberships.isEmpty() && state.offline && state.savedAt == null) {
                Text("Tus establecimientos aparecerán aquí al volver la señal.", fontSize = 14.sp)
            } else if (state.memberships.isEmpty()) {
                Text("Aún no tienes un local registrado. Solicítalo para poder publicar menús.", fontSize = 14.sp)
            } else {
                state.memberships.forEach { membership ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(membership.establishmentName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Note("${membership.area} · ${if (membership.approved) "Aprobado para publicar" else "Pendiente de aprobación"}")
                    }
                }
            }
            val canRefresh = !state.working && !state.offline
            OutlinedButton(onClick = vm::refreshStatus, enabled = canRefresh) {
                Text(if (state.working) "Actualizando…" else "Actualizar estado", color = ink(canRefresh))
            }

            Row(
                Modifier.fillMaxWidth().clickable(onClick = vm::toggleForm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Solicitar un establecimiento", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(if (state.formOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            if (state.formOpen) RequestForm(state, vm)

            state.message?.let { Text(it, fontSize = 13.sp, color = Palette.Ink) }
        }
    }
}

@Composable
private fun RequestForm(state: EstablishmentsUiState, vm: EstablishmentsViewModel) {
    val form = state.form
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Note("Un administrador revisará la solicitud antes de habilitar las publicaciones.")
        Field(form.name, "Nombre del local") { value -> vm.updateForm { it.copy(name = value.take(80)) } }
        Label("Área")
        ChipRow(
            options = EstablishmentsViewModel.AREAS,
            isSelected = { it == form.area },
            label = { it },
            onSelect = { area -> vm.updateForm { it.copy(area = area) } },
        )
        Field(form.address, "Dirección") { vm.setAddress(it.take(160)) }
        PinPicker(state, vm)
        Field(form.entranceDescription, "Cómo encontrar la entrada (opcional)") { value ->
            vm.updateForm { it.copy(entranceDescription = value.take(200)) }
        }
        Field(form.photoUrl, "Enlace HTTPS de foto (opcional)", KeyboardType.Uri) { value ->
            vm.updateForm { it.copy(photoUrl = value.take(500)) }
        }
        Note("La foto del local aparecerá en sus menús. Usa una imagen pública con permiso para mostrarla.")
        if (!form.photoUrlValid) {
            Text("Usa un enlace HTTPS válido, sin usuario ni contraseña.", fontSize = 12.sp, color = Palette.Coral)
        }
        Label("Medios de pago")
        ChipRow(
            options = EstablishmentsViewModel.PAYMENT_METHODS,
            isSelected = { it in form.paymentMethods },
            label = { it },
            onSelect = vm::togglePayment,
        )
        Button(
            onClick = vm::submit,
            enabled = form.canSubmit && !state.working && !state.offline,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink),
        ) {
            Text(if (state.working) "Enviando…" else "Enviar solicitud")
        }
    }
}

/** Pin of the establishment: from the address or the GPS, then the owner taps the map to fix it. */
@Composable
private fun PinPicker(state: EstablishmentsUiState, vm: EstablishmentsViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) vm.useMyLocation() else vm.locationDenied()
    }
    val askLocation = {
        val granted = listOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PERMISSION_GRANTED }
        if (granted) vm.useMyLocation() else permission.launch(arrayOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Ubicación del local")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Stays enabled offline: tapping it explains that the GPS and the map work without signal.
            val canSearch = !state.geocoding && state.form.address.isNotBlank()
            OutlinedButton(onClick = vm::findAddress, enabled = canSearch) {
                Text(if (state.geocoding) "Buscando…" else "Buscar dirección", color = ink(canSearch), fontSize = 13.sp)
            }
            OutlinedButton(onClick = askLocation, enabled = !state.locating) {
                Text(if (state.locating) "Ubicando…" else "Usar mi ubicación", color = Palette.Ink, fontSize = 13.sp)
            }
        }
        state.form.pin?.let { pin ->
            OsmMap(
                pin,
                state.form.name.ifBlank { "Tu local" },
                Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(10.dp)),
                onTap = vm::movePin,
            )
            Note("¿Es aquí tu local? Toca el mapa para mover el pin.")
        }
        state.pinMessage?.let { Note(it) }
        Note("Los estudiantes usan este pin para ver el mapa, abrir indicaciones y sugerir su zona.")
    }
}

/** Admin: establishment requests. Approving one turns its owner into a restaurant. */
@Composable
fun AdminApprovalsSection(vm: AdminViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title("Solicitudes de restaurantes")
            if (state.offline) {
                DemoNotice(savedNotice("solicitudes guardadas", state.requestsSavedAt) + " · aprobar se activa al volver la señal")
            }
            OutlinedButton(onClick = vm::refreshRequests, enabled = !state.offline) {
                Text("Actualizar solicitudes", color = ink(!state.offline))
            }
            if (state.requests.isEmpty()) Note("No hay solicitudes pendientes.")
            state.requests.forEach { request ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(request.establishmentName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Note("${request.area} · ${request.displayName}")
                    Button(
                        onClick = { vm.approve(request) },
                        enabled = state.workingId == null && !state.offline,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink),
                    ) {
                        Text(if (state.workingId == request.key) "Aprobando…" else "Aprobar local")
                    }
                }
            }
            state.requestsMessage?.let { Text(it, fontSize = 13.sp) }
        }
    }
}

/** Admin: grant or take back the admin role. Restaurants are enabled by approving their request. */
@Composable
fun AdminRolesSection(vm: AdminViewModel, currentUserId: String?) {
    val state by vm.state.collectAsStateWithLifecycle()
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title("Administrar roles")
            Note(
                "Solo otro administrador puede conceder o quitar el rol de administrador. " +
                    "Los restaurantes se habilitan aprobando su solicitud de local.",
            )
            if (state.offline) {
                DemoNotice(savedNotice("usuarios guardados", state.usersSavedAt) + " · cambiar roles se activa al volver la señal")
            }
            OutlinedButton(onClick = vm::refreshUsers, enabled = !state.offline) {
                Text("Actualizar usuarios", color = ink(!state.offline))
            }
            state.users.forEach { user ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(user.displayName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Note("${roleName(user.role)} · ${user.id.take(8)}")
                    }
                    if (user.id != currentUserId) {
                        val canChange = state.workingId == null && !state.offline
                        TextButton(onClick = { vm.askRoleChange(user) }, enabled = canChange) {
                            Text(
                                if (user.role == "admin") "Quitar admin" else "Hacer admin",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = ink(canChange),
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
            state.usersMessage?.let { Text(it, fontSize = 13.sp) }
        }
    }

    state.confirming?.let { user ->
        val action = if (user.role == "admin") "Quitar" else "Conceder"
        AlertDialog(
            onDismissRequest = vm::cancelRoleChange,
            title = { Text("Confirmar cambio de rol") },
            text = { Text("¿$action el rol de administrador a ${user.displayName}?") },
            confirmButton = { TextButton(onClick = vm::confirmRoleChange) { Text("Confirmar") } },
            dismissButton = { TextButton(onClick = vm::cancelRoleChange) { Text("Cancelar") } },
        )
    }
}

/** Text colors are set by hand here, so a disabled button must also look disabled (grayed out). */
private fun ink(enabled: Boolean) = if (enabled) Palette.Ink else Palette.Ink.copy(alpha = 0.4f)

/** "Sin conexión · solicitudes guardadas a las 10:35 p. m." or just "Sin conexión" when nothing was saved. */
private fun savedNotice(what: String, savedAt: Instant?): String =
    if (savedAt != null) "Sin conexión · $what a las ${SpanishPresentation.time(savedAt)}" else "Sin conexión"

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
}

@Composable
private fun Label(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Field(value: String, label: String, keyboardType: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
