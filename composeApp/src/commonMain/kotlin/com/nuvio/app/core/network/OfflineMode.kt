package com.nuvio.app.core.network

/**
 * Offline mode: the app runs without the Nuvio account/backend (Supabase).
 *
 * - The sign-in screen is skipped; the app starts as a local ("anonymous") user.
 * - Nothing is synced, and no request is sent to the backend.
 * - Addons, plugins, TMDB and your streaming sources still use the internet as usual.
 *
 * To go back online once a backend exists, either set [FORCE_OFFLINE] to false and rebuild,
 * or add your own server in the app (a custom server turns offline mode off by itself).
 */
object OfflineMode {
    private const val FORCE_OFFLINE = true

    val enabled: Boolean
        get() = FORCE_OFFLINE && !ServerConfigurationRepository.active.value.isCustom
}
