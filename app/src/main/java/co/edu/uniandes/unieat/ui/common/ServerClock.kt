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
        // produceState keeps the old value when the key changes, so refresh right away
        while (true) {
            value = Instant.now() + offset
            delay(60_000)
        }
    }
    return now
}
