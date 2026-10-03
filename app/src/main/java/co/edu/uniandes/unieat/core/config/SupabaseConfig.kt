package co.edu.uniandes.unieat.core.config

import co.edu.uniandes.unieat.BuildConfig

data class SupabaseConfig(
    val url: String,
    val publishableKey: String,
) {
    val isConfigured: Boolean
        get() = (url.startsWith("https://") || url.startsWith("http://")) && publishableKey.isNotBlank()

    val apiBaseUrl: String get() = "${url.trimEnd('/')}/functions/v1/api-v1"

    val authBaseUrl: String get() = "${url.trimEnd('/')}/auth/v1"

    companion object {
        fun fromBuildConfig() = SupabaseConfig(
            url = BuildConfig.SUPABASE_URL,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        )
    }
}
