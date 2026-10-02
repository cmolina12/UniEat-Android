package co.edu.uniandes.unieat.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * Server-aligned clock that ticks every minute, like iOS `TimelineView(.periodic(by: 60))`.
 * [offset] = server time − device time, captured when the data was fetched.
 */
@Composable
fun rememberServerNow(offset: Duration): Instant {
    val now by produceState(Instant.now() + offset, offset) {
        while (true) {
            delay(60_000)
            value = Instant.now() + offset
        }
    }
    return now
}
