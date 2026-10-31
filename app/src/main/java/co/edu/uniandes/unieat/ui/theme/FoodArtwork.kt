package co.edu.uniandes.unieat.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** The place's photo over the drawing with its name (the drawing shows while it loads or if it fails). Like iOS FoodArtwork. */
@Composable
fun FoodArtwork(name: String, photoUrl: String?, height: Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(11.dp))
            .background(Brush.linearGradient(listOf(Palette.Yellow, Palette.Coral.copy(alpha = 0.8f)))),
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 20.dp, y = (-10).dp)
                .size(140.dp)
                .background(Color.White.copy(alpha = 0.45f), CircleShape),
        )
        photoUrl?.takeIf { it.isNotBlank() }?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = "Foto de $name",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            name.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            color = Palette.Ink,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .background(Palette.Paper, CircleShape)
                .padding(7.dp),
        )
    }
}
