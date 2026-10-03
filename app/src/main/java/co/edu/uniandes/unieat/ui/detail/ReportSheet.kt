package co.edu.uniandes.unieat.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.ReportKind
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import kotlin.math.roundToInt

private const val NOTE_MAX = 280

private val choices: List<Triple<ReportKind, String, ImageVector>> = listOf(
    Triple(ReportKind.UNAVAILABLE, "Plato agotado o no disponible", Icons.Filled.Close),
    Triple(ReportKind.PRICE, "Precio distinto al publicado", Icons.Filled.ShoppingCart),
    Triple(ReportKind.LONG_LINE, "Fila mucho más larga", Icons.Filled.Person),
    Triple(ReportKind.LOCATION, "Ubicación difícil de encontrar", Icons.Filled.Place),
    Triple(ReportKind.ACCURATE, "El menú sigue correcto", Icons.Filled.ThumbUp),
    Triple(ReportKind.ARRIVAL, "Llegué al local", Icons.Filled.Done),
)

private val ReportKind.isDiscrepancy: Boolean
    get() = this == ReportKind.UNAVAILABLE || this == ReportKind.PRICE || this == ReportKind.LOCATION

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    menu: DailyMenu,
    initialKind: ReportKind,
    submission: ReportSubmission,
    onSubmit: (kind: ReportKind, note: String, observedWaitMinutes: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var note by rememberSaveable { mutableStateOf("") }
    var waitMinutes by rememberSaveable { mutableIntStateOf(20) }
    val sending = submission is ReportSubmission.Sending

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Cream,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            BrandHeader("Reportar un cambio")
            Text("${menu.establishmentName} · versión ${menu.version} del menú", style = MaterialTheme.typography.bodyMedium)

            if (submission is ReportSubmission.Sent) {
                SurfaceCard(Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.Ink)
                        Text(submission.message, style = MaterialTheme.typography.titleMedium)
                    }
                    if (submission.pending) {
                        Text(
                            "Mientras se revisa, otros estudiantes verán un aviso en este menú.",
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                SolidButton("Cerrar", onClick = onDismiss, color = Palette.Yellow)
                return@Column
            }

            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { (choice, label, icon) ->
                    val selected = kind == choice
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (selected) Palette.Yellow else Palette.Paper, RoundedCornerShape(12.dp))
                            .border(2.dp, Palette.Ink, RoundedCornerShape(12.dp))
                            .selectable(selected = selected, enabled = !sending, role = Role.RadioButton) { kind = choice }
                            .padding(horizontal = 13.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(icon, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(20.dp))
                        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.weight(1f))
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Palette.Ink, unselectedColor = Palette.Ink),
                        )
                    }
                }
            }

            if (kind == ReportKind.LONG_LINE) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.Paper, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Text("Tiempo observado: $waitMinutes min", fontWeight = FontWeight.Bold)
                    Slider(
                        value = waitMinutes.toFloat(),
                        onValueChange = { waitMinutes = (it / 5).roundToInt() * 5 },
                        valueRange = 0f..120f,
                        steps = 23,
                        enabled = !sending,
                        colors = SliderDefaults.colors(thumbColor = Palette.Ink, activeTrackColor = Palette.Ink),
                    )
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(NOTE_MAX) },
                label = { Text("Comentario opcional") },
                supportingText = { Text("${note.length}/$NOTE_MAX") },
                minLines = 3,
                maxLines = 5,
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Palette.Paper,
                    unfocusedContainerColor = Palette.Paper,
                    focusedBorderColor = Palette.Ink,
                    focusedLabelColor = Palette.Ink,
                    cursorColor = Palette.Ink,
                ),
            )

            Text(
                if (kind.isDiscrepancy) {
                    "Tu reporte no modifica el menú oficial automáticamente. Otros usuarios verán una advertencia mientras se verifica."
                } else {
                    "Es una observación: ayuda a otros estudiantes y no cambia el menú publicado."
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (submission is ReportSubmission.Failed) {
                Text(
                    submission.message,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Palette.Coral.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }

            SolidButton(
                if (sending) "Enviando…" else "Enviar reporte comunitario",
                onClick = { onSubmit(kind, note, if (kind == ReportKind.LONG_LINE) waitMinutes else null) },
                icon = Icons.AutoMirrored.Filled.Send,
                enabled = !sending,
            )
        }
    }
}
