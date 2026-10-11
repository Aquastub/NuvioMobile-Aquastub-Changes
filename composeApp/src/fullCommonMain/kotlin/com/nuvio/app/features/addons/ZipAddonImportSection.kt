package com.nuvio.app.features.addons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.addons_zip_confirm_install
import nuvio.composeapp.generated.resources.addons_zip_confirm_message
import nuvio.composeapp.generated.resources.addons_zip_confirm_title
import nuvio.composeapp.generated.resources.addons_zip_description
import nuvio.composeapp.generated.resources.addons_zip_failure_title
import nuvio.composeapp.generated.resources.addons_zip_install_button
import nuvio.composeapp.generated.resources.addons_zip_installing
import nuvio.composeapp.generated.resources.addons_zip_success_message
import nuvio.composeapp.generated.resources.addons_zip_success_title
import nuvio.composeapp.generated.resources.addons_zip_updated_message
import nuvio.composeapp.generated.resources.addons_zip_wrong_extension
import org.jetbrains.compose.resources.stringResource

private sealed interface ZipImportState {
    data class Confirm(val bytes: ByteArray, val fileName: String) : ZipImportState
    data object Installing : ZipImportState
    data class Done(val message: String, val success: Boolean) : ZipImportState
}

@Composable
internal fun ZipAddonImportSection() {
    if (!ZipAddonPlatform.supported) return

    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ZipImportState?>(null) }
    val successTitle = stringResource(Res.string.addons_zip_success_title)
    val failureTitle = stringResource(Res.string.addons_zip_failure_title)
    val successMessage = stringResource(Res.string.addons_zip_success_message)
    val updatedMessage = stringResource(Res.string.addons_zip_updated_message)

    val wrongExtensionMessage = stringResource(Res.string.addons_zip_wrong_extension)
    val pickZip = rememberZipAddonPicker { bytes, fileName ->
        state = if (fileName.endsWith(".adinst", ignoreCase = true)) {
            ZipImportState.Confirm(bytes, fileName)
        } else {
            ZipImportState.Done(message = wrongExtensionMessage, success = false)
        }
    }

    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                text = stringResource(Res.string.addons_zip_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(14.dp))
            NuvioPrimaryButton(
                text = stringResource(Res.string.addons_zip_install_button),
                onClick = pickZip,
            )
        }
    }

    when (val current = state) {
        null -> Unit
        is ZipImportState.Confirm -> NuvioStatusModal(
            title = stringResource(Res.string.addons_zip_confirm_title),
            message = stringResource(Res.string.addons_zip_confirm_message, current.fileName),
            isVisible = true,
            confirmText = stringResource(Res.string.addons_zip_confirm_install),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                state = ZipImportState.Installing
                scope.launch {
                    state = when (val result = ZipAddonManager.install(current.bytes)) {
                        is ZipAddonManager.InstallResult.Success -> ZipImportState.Done(
                            message = "${result.name}: ${if (result.updated) updatedMessage else successMessage}",
                            success = true,
                        )
                        is ZipAddonManager.InstallResult.Error -> ZipImportState.Done(result.message, success = false)
                    }
                }
            },
            onDismiss = { state = null },
        )
        ZipImportState.Installing -> NuvioStatusModal(
            title = stringResource(Res.string.addons_zip_installing),
            message = "",
            isVisible = true,
            isBusy = true,
            confirmText = stringResource(Res.string.addons_zip_installing),
            onConfirm = {},
        )
        is ZipImportState.Done -> NuvioStatusModal(
            title = if (current.success) successTitle else failureTitle,
            message = current.message,
            isVisible = true,
            confirmText = stringResource(if (current.success) Res.string.action_done else Res.string.action_close),
            onConfirm = { state = null },
        )
    }
}
