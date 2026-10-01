package co.edu.uniandes.unieat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Brand colors, same values as `Palette` in Theme.swift. */
object Palette {
    val Yellow = Color(0xFFFFE500)
    val Cyan = Color(0xFF19D3E8)
    val Green = Color(0xFF8BE000)
    val Coral = Color(0xFFFF5A36)
    val Ink = Color(0xFF121212)
    val Cream = Color(0xFFF5F0E6)
    val Paper = Color.White
}

// iOS is light-only with ink text on bright fills; Android mirrors that.
private val UniEatColors = lightColorScheme(
    primary = Palette.Ink,
    onPrimary = Color.White,
    secondary = Palette.Coral,
    onSecondary = Palette.Ink,
    tertiary = Palette.Cyan,
    onTertiary = Palette.Ink,
    background = Palette.Cream,
    onBackground = Palette.Ink,
    surface = Palette.Paper,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Cream,
    onSurfaceVariant = Palette.Ink.copy(alpha = 0.6f),
    outline = Palette.Ink,
    error = Palette.Coral,
    onError = Palette.Ink,
)

// iOS uses SF Rounded heavy weights; Android uses the system font with the same sizes/weights.
private val UniEatTypography = Typography(
    headlineSmall = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.ExtraBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun UniEatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = UniEatColors, typography = UniEatTypography, content = content)
}
