package co.edu.uniandes.unieat.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale

// Ports of the reusable views in Theme.swift: BrandHeader, Sticker, SolidButton, SurfaceCard, DemoNotice.

private val SpanishColombia: Locale = Locale.forLanguageTag("es-CO")

/** COP price as shown on iOS: `$12.000`. */
val Int.cop: String
    get() = "$" + NumberFormat.getIntegerInstance(SpanishColombia).format(this).replace(',', '.')

/** Stand-in for SF Symbol `bolt.fill` (not in material-icons-core). */
private val Bolt: ImageVector = ImageVector.Builder("Bolt", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(13f, 2f); lineTo(4f, 14f); lineTo(11f, 14f); lineTo(10f, 22f)
        lineTo(20f, 9f); lineTo(13f, 9f); close()
    }
}.build()

@Composable
fun BrandHeader(title: String, subtitle: String = "Uniandes · En campus") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            Modifier.size(29.dp).background(Palette.Ink, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.Ink.copy(alpha = 0.6f))
        }
        Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = Palette.Ink)
    }
}

@Composable
fun Sticker(text: String, color: Color = Palette.Yellow, icon: ImageVector? = null) {
    Row(
        Modifier
            .background(color, CircleShape)
            .border(1.4.dp, Palette.Ink, CircleShape)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.Ink)
    }
}

@Composable
fun SolidButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Palette.Coral,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        shape = shape,
        border = BorderStroke(2.dp, Palette.Ink),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Palette.Ink),
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 7.dp))
        if (icon != null) {
            Spacer(Modifier.width(8.dp))
            Icon(icon, contentDescription = null)
        }
    }
}

@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .background(Palette.Paper, shape)
            .border(2.dp, Palette.Ink, shape)
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun DemoNotice(text: String = "Modo demostración · contenido generado para probar la app") {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Cyan.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
    }
}
