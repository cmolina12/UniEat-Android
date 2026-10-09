package co.edu.uniandes.unieat.core.config

import co.edu.uniandes.unieat.BuildConfig

data class SupabaseConfig(
    val url: String,
    val publishableKey: String,
) {
    val isConfigured: Boolean
        get() = (url.startsWith("https://") || url.startsWith("http://")) && publishableKey.isNotBlank()

    /** A `supabase start` stack on this machine (seed.sql data), reached from the emulator or the host. */
    val isLocal: Boolean
        get() = listOf("10.0.2.2", "localhost", "127.0.0.1").any { url.contains("://$it") }

    val apiBaseUrl: String get() = "${url.trimEnd('/')}/functions/v1/api-v1"

    val authBaseUrl: String get() = "${url.trimEnd('/')}/auth/v1"

    companion object {
        fun fromBuildConfig() = SupabaseConfig(
            url = BuildConfig.SUPABASE_URL,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        )
    }
}
