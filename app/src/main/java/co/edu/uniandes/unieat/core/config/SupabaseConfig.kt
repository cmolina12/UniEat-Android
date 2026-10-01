package co.edu.uniandes.unieat.core.config

import co.edu.uniandes.unieat.BuildConfig

/**
 * Supabase project settings, injected into BuildConfig from the git-ignored local.properties
 * (`supabase.url`, `supabase.publishableKey`). Android equivalent of iOS AppConfiguration.
 */
data class SupabaseConfig(
    val url: String,
    val publishableKey: String,
) {
    val isConfigured: Boolean
        get() = (url.startsWith("https://") || url.startsWith("http://")) && publishableKey.isNotBlank()

    /** Shared API v1 for iOS and Android (Edge Function `api-v1`). */
    val apiBaseUrl: String get() = "${url.trimEnd('/')}/functions/v1/api-v1"

    /** Supabase Auth (GoTrue) base, used only for login/refresh. */
    val authBaseUrl: String get() = "${url.trimEnd('/')}/auth/v1"

    companion object {
        fun fromBuildConfig() = SupabaseConfig(
            url = BuildConfig.SUPABASE_URL,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        )
    }
}
