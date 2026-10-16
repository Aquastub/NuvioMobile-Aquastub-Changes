package com.nuvio.app.features.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private val actionJson = Json { ignoreUnknownKeys = true }

/**
 * The buttons addons add to the player (declared in their manifest under nuvio.playerActions).
 * Tapping one sends an "action" request to the addon, which answers with an optional toast,
 * a new label and an "active" flag (for toggles such as Record / Stop recording).
 */
@Composable
internal fun AddonPlayerActions(context: PlayerAddonContext, modifier: Modifier = Modifier) {
    val entries = LocalAddonRegistry.all().flatMap { addon ->
        addon.playerActions.filter { it.matches(context.streamUrl) }.map { addon to it }
    }
    if (entries.isEmpty()) return

    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(3_500)
            toast = null
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { (addon, action) ->
            AddonActionButton(addon = addon, action = action, context = context, onToast = { toast = it })
        }
        toast?.let { message ->
            Text(
                text = message,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AddonActionButton(
    addon: LocalAddon,
    action: AddonPlayerAction,
    context: PlayerAddonContext,
    onToast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var label by remember(addon.slug, action.id) { mutableStateOf(action.label) }
    var active by remember(addon.slug, action.id) { mutableStateOf(false) }
    var busy by remember(addon.slug, action.id) { mutableStateOf(false) }

    // Ask the addon for the current state (for example "already recording this channel").
    LaunchedEffect(addon.slug, action.id, context.streamUrl) {
        val reply = callAction(addon, action, context, query = "status") ?: return@LaunchedEffect
        reply.label?.let { label = it }
        reply.active?.let { active = it }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Color(0xDDB3261E) else Color(0x99000000))
            .clickable(enabled = !busy) {
                busy = true
                scope.launch {
                    val reply = callAction(addon, action, context, query = null)
                    reply?.label?.let { label = it }
                    reply?.active?.let { active = it }
                    reply?.toast?.let(onToast)
                    busy = false
                }
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (active) Color.White else Color(0xFFE53935)),
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private data class ActionReply(val toast: String?, val label: String?, val active: Boolean?)

private suspend fun callAction(
    addon: LocalAddon,
    action: AddonPlayerAction,
    context: PlayerAddonContext,
    query: String?,
): ActionReply? {
    val extra = buildMap {
        put("streamUrl", context.streamUrl)
        put("title", context.title)
        put("streamTitle", context.streamTitle)
        put("providerName", context.providerName)
        if (query != null) put("query", query)
    }
    val raw = runCatching {
        addon.handle(LocalAddonRequest(resource = "action", type = "player", id = action.id, extra = extra))
    }.getOrNull() ?: return null
    val obj = runCatching { actionJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    return ActionReply(
        toast = (obj["toast"] as? JsonPrimitive)?.contentOrNull,
        label = (obj["label"] as? JsonPrimitive)?.contentOrNull?.take(24),
        active = (obj["active"] as? JsonPrimitive)?.booleanOrNull,
    )
}

private fun AddonPlayerAction.matches(url: String): Boolean {
    val pattern = match ?: return true
    return runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(url) }.getOrDefault(true)
}
