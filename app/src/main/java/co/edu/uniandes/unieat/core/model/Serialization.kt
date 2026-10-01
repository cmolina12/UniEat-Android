package co.edu.uniandes.unieat.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * ISO 8601 UTC with milliseconds (`2026-09-28T17:00:00.000Z`), as api-v1 sends and expects.
 * Also accepts dates without fraction or with an explicit offset, like UniEatDates.decoder() on iOS.
 */
object InstantSerializer : KSerializer<Instant> {
    private val output = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(output.format(value))

    override fun deserialize(decoder: Decoder): Instant =
        Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(decoder.decodeString()))
}

/** Shared JSON settings: extra server fields are ignored, `null` falls back to defaults, nulls are not sent. */
val UniEatJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}
