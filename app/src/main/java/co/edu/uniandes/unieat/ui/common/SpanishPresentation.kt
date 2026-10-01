package co.edu.uniandes.unieat.ui.common

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Port of iOS SpanishPresentation: visible labels and dates in Spanish, domain values unchanged. */
object SpanishPresentation {
    val locale: Locale = Locale.forLanguageTag("es-CO")

    fun diet(value: String?): String = when (value) {
        null -> "Todas"
        "vegetarian" -> "Vegetariana"
        "vegan" -> "Vegana"
        else -> "Otra preferencia"
    }

    fun dietaryTags(values: List<String>): String =
        values.joinToString(", ") { diet(it).lowercase(locale) }

    fun dateAndTime(instant: Instant): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale).withZone(ZoneId.systemDefault()).format(instant)

    fun time(instant: Instant): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale).withZone(ZoneId.systemDefault()).format(instant)

    /** "menos de 10 m", "350 m" (rounded to 10 m), "1,2 km". */
    fun distance(meters: Double): String = when {
        meters < 10 -> "menos de 10 m"
        meters < 1_000 -> "${(Math.round(meters / 10) * 10).coerceAtMost(990)} m"
        else -> String.format(locale, "%.1f km", meters / 1_000)
    }

    /** "hace 5 min", "hace 2 h", "hace 3 días". A future [instant] (clock skew) reads as "hace un momento". */
    fun relative(instant: Instant, now: Instant): String {
        val minutes = Duration.between(instant, now).toMinutes()
        return when {
            minutes < 1 -> "hace un momento"
            minutes < 60 -> "hace $minutes min"
            minutes < 24 * 60 -> "hace ${minutes / 60} h"
            minutes < 48 * 60 -> "hace 1 día"
            else -> "hace ${minutes / (24 * 60)} días"
        }
    }
}
