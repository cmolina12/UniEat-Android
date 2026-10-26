package co.edu.uniandes.unieat.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first

/**
 * Calls [onDismiss] once a sheet that was shown ends up hidden. With Material3 1.3.1 the system back
 * gesture (Android 14+) slides a ModalBottomSheet away without calling onDismissRequest, so the caller
 * never removes it and its invisible window keeps covering the screen and swallowing every touch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DismissWhenHidden(sheetState: SheetState, onDismiss: () -> Unit) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.isVisible }.dropWhile { !it }.first { !it }
        currentOnDismiss()
    }
}
