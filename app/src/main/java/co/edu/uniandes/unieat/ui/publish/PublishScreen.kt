package co.edu.uniandes.unieat.ui.publish

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.ui.common.SpanishPresentation
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.ChipRow
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.Sticker
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun PublishScreen(vm: PublishViewModel, onSessionExpired: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val now = Instant.now()

    LaunchedEffect(state.sessionExpired) {
        if (state.sessionExpired) onSessionExpired()
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        BrandHeader("UniEat · Publicar")
        Text(
            if (state.editing == null) "Publicar menú del día" else "Editar menú del día",
            fontSize = 27.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Palette.Ink,
        )
        Text(
            "Completa los campos estructurados para que estudiantes puedan comparar y filtrar tu oferta.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
        offlineNotice(state)?.let { DemoNotice(it) }
        EstablishmentSection(state, vm)
        MenuSection(state.form, vm)
        state.form.dishes.forEach { dish ->
            DishCard(dish, removable = state.form.dishes.size > 1, vm)
        }
        if (state.form.dishes.size < PublishViewModel.MAX_DISHES) {
            OutlinedButton(onClick = vm::addDish, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text("Agregar otro plato", fontWeight = FontWeight.Bold, color = Palette.Ink)
            }
        }
        state.message?.let { message ->
            Text(
                message,
                color = Palette.Ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Cyan, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            )
        }
        val missing = state.form.missing(now)
        if (missing.isNotEmpty() && !state.loading) {
            Text(
                "Para publicar falta: " + missing.joinToString(", ") + ".",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SolidButton(
            title = when {
                state.saving -> "Guardando…"
                state.editing == null -> "Publicar menú ahora"
                else -> "Guardar cambios"
            },
            onClick = vm::publish,
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            color = Palette.Coral,
            enabled = state.form.isValid(now) && !state.saving && !state.offline,
        )
        if (state.editing != null || state.form.hasInput) {
            OutlinedButton(onClick = vm::discardDraft, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.editing != null) "Cancelar edición" else "Descartar borrador", color = Palette.Ink)
            }
        }
        MyMenus(state.myMenus, now, canClose = !state.offline, onEdit = vm::edit, onClose = vm::close)
    }
}

@Composable
private fun EstablishmentSection(state: PublishUiState, vm: PublishViewModel) {
    val form = state.form
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Tu establecimiento")
            when {
                state.editing != null -> Sticker(form.establishmentName, color = Palette.Yellow)
                state.approved.isEmpty() && state.offline && state.savedAt == null -> Text(
                    "Sin conexión: tus establecimientos aparecerán aquí al volver la señal. Puedes ir llenando el menú.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.approved.isEmpty() && !state.loading -> Text(
                    "No tienes establecimientos aprobados. Solicita uno o actualiza su estado en Perfil.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> ChipRow(
                    options = state.approved,
                    isSelected = { it.id == form.establishmentId },
                    label = { it.name },
                    onSelect = { vm.selectEstablishment(it.id) },
                )
            }
            FormField(form.establishmentName, "Nombre del restaurante") { value ->
                vm.updateForm { it.copy(establishmentName = value.take(80)) }
            }
            Text("Área", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            ChipRow(
                options = PublishViewModel.AREAS,
                isSelected = { it == form.area },
                label = { it },
                onSelect = { area -> vm.updateForm { it.copy(area = area) } },
            )
            FormField(form.address, "Dirección o referencia de entrada") { value ->
                vm.updateForm { it.copy(address = value.take(160)) }
            }
            FormField(form.entranceDescription, "Cómo encontrar la entrada (opcional)") { value ->
                vm.updateForm { it.copy(entranceDescription = value.take(200)) }
            }
            Text("Medios de pago aceptados", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            ChipRow(
                options = PublishViewModel.PAYMENT_METHODS,
                isSelected = { it in form.paymentMethods },
                label = { it },
                onSelect = vm::togglePayment,
            )
        }
    }
}

@Composable
private fun MenuSection(form: PublishForm, vm: PublishViewModel) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Menú de hoy")
            FormField(form.title, "Nombre del menú") { value -> vm.updateForm { it.copy(title = value.take(120)) } }
            ValidUntilPicker(form.validUntil) { instant -> vm.updateForm { it.copy(validUntil = instant) } }
            Text(
                "La publicación dejará de aparecer al vencer. La vigencia máxima es de 24 horas.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Válido hasta": a date picker and then a time picker, in the phone's time zone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ValidUntilPicker(validUntil: Instant, onChange: (Instant) -> Unit) {
    val zone = ZoneId.systemDefault()
    val local = validUntil.atZone(zone)
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }

    Text("Válido hasta", fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickingDate = true }) {
            Text(SpanishPresentation.date(validUntil), color = Palette.Ink)
        }
        OutlinedButton(onClick = { pickingTime = true }) {
            Text(SpanishPresentation.time(validUntil), color = Palette.Ink)
        }
    }

    if (pickingDate) {
        // The DatePicker works with UTC midnight of the chosen day.
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(day.atTime(local.toLocalTime()).atZone(zone).toInstant())
                    }
                    pickingDate = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancelar") } },
        ) {
            DatePicker(dateState)
        }
    }

    if (pickingTime) {
        val timeState = rememberTimePickerState(initialHour = local.hour, initialMinute = local.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    val day: LocalDate = local.toLocalDate()
                    onChange(day.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant())
                    pickingTime = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancelar") } },
            text = { TimePicker(timeState) },
        )
    }
}

@Composable
private fun DishCard(dish: DishDraft, removable: Boolean, vm: PublishViewModel) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Plato", Modifier.weight(1f))
                if (removable) {
                    IconButton(onClick = { vm.removeDish(dish.key) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Quitar plato", tint = Palette.Coral)
                    }
                }
            }
            FormField(dish.name, "Nombre del plato") { value -> vm.updateDish(dish.key) { it.copy(name = value.take(80)) } }
            FormField(dish.description, "Descripción opcional") { value ->
                vm.updateDish(dish.key) { it.copy(description = value.take(280)) }
            }
            FormField(dish.priceText, "Precio en COP", KeyboardType.Number) { value ->
                vm.updateDish(dish.key) { it.copy(priceText = value.filter(Char::isDigit).take(7)) }
            }
            SwitchRow("Información dietaria confirmada", dish.dietaryKnown) { on ->
                vm.updateDish(dish.key) { it.copy(dietaryKnown = on) }
            }
            if (dish.dietaryKnown) {
                SwitchRow("Vegetariano", dish.vegetarian) { on -> vm.updateDish(dish.key) { it.copy(vegetarian = on) } }
                SwitchRow("Vegano", dish.vegan) { on -> vm.updateDish(dish.key) { it.copy(vegan = on) } }
            }
        }
    }
}

@Composable
private fun MyMenus(menus: List<DailyMenu>, now: Instant, canClose: Boolean, onEdit: (DailyMenu) -> Unit, onClose: (DailyMenu) -> Unit) {
    if (menus.isEmpty()) {
        SurfaceCard(Modifier.fillMaxWidth()) {
            Text("Aún no has publicado un menú del día.", fontSize = 14.sp)
        }
        return
    }
    Text("Mis publicaciones", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
    menus.forEach { menu ->
        SurfaceCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(menu.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "${menu.establishmentName} · versión ${menu.version} · ${menu.ownerStatus(now)} · " +
                        "vence ${SpanishPresentation.time(menu.validUntil)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (menu.closedAt == null) {
                        OutlinedButton(onClick = { onEdit(menu) }) { Text("Editar", color = Palette.Ink) }
                    }
                    if (menu.closedAt == null && menu.isActive(now)) {
                        OutlinedButton(onClick = { onClose(menu) }, enabled = canClose) {
                            Text("Cerrar publicación", color = if (canClose) Palette.Coral else Palette.Ink.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}

/** Says what works offline and what waits for the signal, instead of a generic error. */
private fun offlineNotice(state: PublishUiState): String? = when {
    !state.offline -> null
    state.savedAt != null ->
        "Sin conexión · publicaciones guardadas a las ${SpanishPresentation.time(state.savedAt)} · " +
            "tu borrador se guarda en el teléfono; publicar y cerrar se activan al volver la señal"
    else -> "Sin conexión · tu borrador se guarda en el teléfono; publicar y cerrar se activan al volver la señal"
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink, modifier = modifier)
}

@Composable
private fun FormField(
    value: String,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
