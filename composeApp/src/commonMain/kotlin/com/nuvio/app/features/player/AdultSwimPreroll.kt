package com.nuvio.app.features.player

import com.nuvio.app.features.addons.LocalAddonRegistry
import com.nuvio.app.features.addons.LocalAddonRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Starts the optional ZIP addon bumper only for series playback.
 * The add-on returns a single HTTPS video URL; it does not control the player itself.
 */
internal suspend fun PlayerScreenRuntime.tryStartAdultSwimPreroll() {
    if (!isSeries || adultSwimPrerollActive || activeTorrentInfoHash != null) return
    if (activeSourceUrl != sourceUrl || sourceUrl.isBlank()) return
    val addon = LocalAddonRegistry.get("com-aquastub-nuvio-adultswim-preroll") ?: return

    val bumpUrl = runCatching {
        val response = addon.handle(
            LocalAddonRequest(
                resource = "action",
                type = "series",
                id = "random-preroll",
                extra = emptyMap(),
            ),
        )
        Json.parseToJsonElement(response).jsonObject["url"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.trim()?.takeIf {
        it.startsWith("https://") || it.startsWith("http://")
    } ?: return

    adultSwimPrerollEpisodeUrl = activeSourceUrl
    adultSwimPrerollActive = true
    activeSourceUrl = bumpUrl
    activeSourceIdentityKey = "url:$bumpUrl"
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeTorrentInfoHash = null
    activeTorrentFileIdx = null
    activeTorrentFilename = null
    activeTorrentTrackers = emptyList()
    p2pResolvedSourceUrl = null
    shouldPlay = true
    errorMessage = null
}

internal fun PlayerScreenRuntime.finishAdultSwimPreroll() {
    if (!adultSwimPrerollActive) return
    val episodeUrl = adultSwimPrerollEpisodeUrl ?: sourceUrl
    adultSwimPrerollActive = false
    adultSwimPrerollEpisodeUrl = null
    activeSourceUrl = episodeUrl
    activeSourceIdentityKey = activeTorrentInfoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let {
        "torrent:$it:${activeTorrentFileIdx ?: -1}"
    } ?: "url:$episodeUrl"
    activeSourceHeaders = sanitizePlaybackHeaders(sourceHeaders)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(sourceResponseHeaders)
    activeStreamType = streamType
    shouldPlay = true
    errorMessage = null
}
