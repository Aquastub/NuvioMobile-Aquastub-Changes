package com.nuvio.app.features.addons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.features.settings.SettingsSecretTextField
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.addons_settings_button
import nuvio.composeapp.generated.resources.addons_settings_title
import org.jetbrains.compose.resources.stringResource

/** A "Settings" button on the card of a local addon that declares settings, opening a form for them. */
@Composable
internal fun AddonSettingsEntry(manifestUrl: String) {
    val addon = LocalAddonRegistry.all().firstOrNull { "$LOCAL_ADDON_SCHEME${it.slug}/manifest.json" == manifestUrl }
    if (addon == null || addon.settings.isEmpty()) return

    var open by remember(manifestUrl) { mutableStateOf(false) }
    DialogButton(
        text = stringResource(Res.string.addons_settings_button),
        onClick = { open = true },
        style = DialogButtonStyle.Secondary,
    )
    if (open) {
        AddonSettingsDialog(addon = addon, manifestUrl = manifestUrl, onDismiss = { open = false })
    }
}

@Composable
private fun AddonSettingsDialog(addon: LocalAddon, manifestUrl: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val values = remember(addon.slug) {
        mutableStateMapOf<String, String>().also { it.putAll(ZipAddonPlatform.loadSettings(addon.slug)) }
    }

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.addons_settings_title, addon.displayLabel()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            addon.settings.forEach { setting ->
                val value = values[setting.key].orEmpty()
                if (setting.type == "password") {
                    SettingsSecretTextField(
                        value = value,
                        onValueChange = { values[setting.key] = it },
                        label = setting.label,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { values[setting.key] = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(setting.label) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (setting.type) {
                                "url" -> KeyboardType.Uri
                                "number" -> KeyboardType.Number
                                else -> KeyboardType.Text
                            },
                        ),
                    )
                }
                setting.hint?.let { hint ->
                    Text(text = hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.action_save),
                style = DialogButtonStyle.Primary,
                onClick = {
                    ZipAddonPlatform.saveSettings(
                        addon.slug,
                        values.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() },
                    )
                    scope.launch { AddonRepository.refreshAddon(manifestUrl, forceRefresh = true) }
                    onDismiss()
                },
            )
        }
    }
}

private fun LocalAddon.displayLabel(): String = (this as? ScriptLocalAddon)?.displayName ?: slug
