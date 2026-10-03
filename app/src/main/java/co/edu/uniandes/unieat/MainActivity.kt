package co.edu.uniandes.unieat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import co.edu.uniandes.unieat.ui.navigation.UniEatNavHost
import co.edu.uniandes.unieat.ui.theme.UniEatTheme

/** Single activity; all screens are Compose destinations. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as UniEatApplication).container
        setContent {
            UniEatTheme { UniEatNavHost(container = container) }
        }
    }
}
