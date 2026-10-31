package co.edu.uniandes.unieat.data.local
import java.time.Duration


// If the user has been waiting for less than 1 minute or more than 45 minutes, we consider the measurement invalid.
fun waitMinutes(start: Long, end: Long): Int? {
    val duration = Duration.ofMillis(end - start)
    val minutes = duration.toMinutes()
    if (minutes < 1 || minutes > 45) {
        return null
    }
    return minutes.toInt()
}
