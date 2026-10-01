package co.edu.uniandes.unieat.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Uniform backend error: `{"error":{"code","message","traceId","field?","details?"}}`. */
@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val traceId: String? = null,
    val field: String? = null,
    val details: JsonObject? = null,
)

@Serializable
internal data class ApiErrorEnvelope(val error: ApiError)

/**
 * Every ApiClient failure. [error.code] drives the UI reaction; [error.message] is already Spanish.
 * [httpStatus] is null when the request never got a response (offline).
 */
class ApiException(
    val error: ApiError,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(error.message, cause) {
    val code: String get() = error.code

    companion object {
        const val OFFLINE = "OFFLINE"
        const val VALIDATION_ERROR = "VALIDATION_ERROR"
        const val AUTH_REQUIRED = "AUTH_REQUIRED"
        const val FORBIDDEN = "FORBIDDEN"
        const val NOT_FOUND = "NOT_FOUND"
        const val VERSION_CONFLICT = "VERSION_CONFLICT"
        const val GONE = "GONE"
        const val RATE_LIMITED = "RATE_LIMITED"
        const val INTERNAL_ERROR = "INTERNAL_ERROR"
        const val UNCONFIGURED = "UNCONFIGURED"

        fun offline(cause: Throwable? = null) = ApiException(
            ApiError(OFFLINE, "Sin conexión con el servidor. Se muestra la última copia guardada."),
            cause = cause,
        )

        fun unexpected(httpStatus: Int? = null, cause: Throwable? = null) = ApiException(
            ApiError(INTERNAL_ERROR, "El servidor respondió con un error inesperado."),
            httpStatus,
            cause,
        )

        fun unconfigured() = ApiException(
            ApiError(UNCONFIGURED, "Configura supabase.url y supabase.publishableKey en local.properties."),
        )

        fun authRequired() = ApiException(
            ApiError(AUTH_REQUIRED, "Tu sesión terminó. Inicia sesión de nuevo."),
        )
    }
}
