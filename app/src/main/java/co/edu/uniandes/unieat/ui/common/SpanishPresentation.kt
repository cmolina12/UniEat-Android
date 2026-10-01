package co.edu.uniandes.unieat.ui.common

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
}
