package com.nuvio.app.features.addons

/** URL scheme used by addons that run inside the app instead of on a server. */
const val LOCAL_ADDON_SCHEME = "local://"

internal fun String.isLocalAddonUrl(): Boolean = startsWith(LOCAL_ADDON_SCHEME)

/**
 * An addon implemented in Kotlin and shipped inside the app.
 *
 * It speaks the normal Stremio protocol, but exchanges JSON strings in-process,
 * so the existing parsers, repositories and UI work on it without changes.
 * Manifest URL: local://<slug>/manifest.json
 */
interface LocalAddon {
    /** Unique, URL-safe name, e.g. "bumpworthy". */
    val slug: String

    /** Stremio manifest JSON. */
    suspend fun manifest(): String

    /** Stremio response JSON for catalog / meta / stream / subtitles requests, or "action" requests from the player. */
    suspend fun handle(request: LocalAddonRequest): String

    /** What the addon asked to be allowed to do ("programs", "playerUi"). Shown to the user at install. */
    val permissions: Set<String> get() = emptySet()

    /** Buttons this addon adds to the video player (needs the "playerUi" permission). */
    val playerActions: List<AddonPlayerAction> get() = emptyList()

    /** Fields the user can fill in for this addon (a playlist link, a login...). Passed back as request.settings. */
    val settings: List<AddonSetting> get() = emptyList()

    /** True for addons the user installed (e.g. from a ZIP) and may remove again. */
    val removable: Boolean get() = false

    /** Called once when a [removable] addon is removed, to delete its files. */
    fun onRemoved() = Unit
}

/** One field of an addon's settings screen. [type] is "text", "url", "password" or "number". */
data class AddonSetting(val key: String, val label: String, val type: String = "text", val hint: String? = null)

/** A button an addon adds to the player. [match] is an optional regex tested against the stream URL. */
data class AddonPlayerAction(val id: String, val label: String, val match: String? = null)

/** What the player tells an addon about what is playing. */
data class PlayerAddonContext(
    val title: String,
    val streamTitle: String,
    val providerName: String,
    val streamUrl: String,
)

data class LocalAddonRequest(
    val resource: String,          // "catalog", "meta", "stream", "subtitles"
    val type: String,              // "movie", "series", ...
    val id: String,                // decoded id (e.g. "tt1234567:1:2" or catalog id)
    val extra: Map<String, String> // decoded extra args, e.g. skip, genre, search
)

/**
 * Addons are registered at startup (see registerFlavorLocalAddons), before
 * AddonRepository.initialize() reads [manifestUrls]. User-installed ZIP addons are loaded there too.
 */
object LocalAddonRegistry {
    private val addons = linkedMapOf<String, LocalAddon>()

    fun all(): List<LocalAddon> = addons.values.toList()

    fun register(addon: LocalAddon) {
        addons[addon.slug] = addon
    }

    fun get(slug: String): LocalAddon? = addons[slug]

    /** Removes a user-installed addon. Returns false only for built-ins, which cannot be removed. */
    internal fun removeUserInstalled(manifestUrl: String): Boolean {
        val slug = manifestUrl.removePrefix(LOCAL_ADDON_SCHEME).substringBefore('/')
        val addon = addons[slug] ?: return true // orphaned entry (files gone): let it be removed
        if (!addon.removable) return false
        addons.remove(slug)
        addon.onRemoved()
        return true
    }

    internal fun manifestUrls(): List<String> =
        addons.keys.map { "$LOCAL_ADDON_SCHEME$it/manifest.json" }

    internal suspend fun fetch(url: String): String {
        val path = url.removePrefix(LOCAL_ADDON_SCHEME).substringBefore("?")
        val segments = path.split('/').filter { it.isNotEmpty() }
        val slug = segments.firstOrNull() ?: error("Invalid local addon URL: $url")
        val addon = addons[slug] ?: error("Local addon '$slug' is not installed")
        val rest = segments.drop(1)

        if (rest.size == 1 && rest[0] == "manifest.json") return addon.manifest()
        require(rest.size == 3 || rest.size == 4) { "Invalid local addon URL: $url" }

        val last = rest.last().removeSuffix(".json")
        val id = (if (rest.size == 3) last else rest[2]).percentDecode()
        val extra = if (rest.size == 4) parseExtra(last) else emptyMap()
        return addon.handle(LocalAddonRequest(rest[0], rest[1], id, extra))
    }

    private fun parseExtra(segment: String): Map<String, String> =
        segment.split('&')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=').percentDecode() to it.substringAfter('=').percentDecode() }
}

private fun String.percentDecode(): String {
    val bytes = ArrayList<Byte>(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        val hex = if (c == '%' && i + 2 < length) substring(i + 1, i + 3).toIntOrNull(16) else null
        if (hex != null) {
            bytes.add(hex.toByte())
            i += 3
        } else {
            c.toString().encodeToByteArray().forEach(bytes::add)
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}

/** Lets a build flavor add UI: the Addons screen's install card, and addon buttons in the player. */
object LocalAddonUi {
    var importContent: (@androidx.compose.runtime.Composable () -> Unit)? = null

    /** Draws a "Settings" entry on an addon's card (receives the addon's manifest URL). */
    var settingsContent: (@androidx.compose.runtime.Composable (String) -> Unit)? = null

    /** Draws the addon-provided buttons for the player. Receives what is playing and a modifier for placement. */
    var playerActions: (@androidx.compose.runtime.Composable (PlayerAddonContext, androidx.compose.ui.Modifier) -> Unit)? = null
}
