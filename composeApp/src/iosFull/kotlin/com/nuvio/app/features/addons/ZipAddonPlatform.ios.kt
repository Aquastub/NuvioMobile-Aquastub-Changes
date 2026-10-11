package com.nuvio.app.features.addons

import androidx.compose.runtime.Composable

// Installing addons from ZIP files is not implemented on iOS yet.
internal object ZipAddonPlatform {
    val supported: Boolean = false

    fun unzip(bytes: ByteArray): Map<String, ByteArray> = error("Not supported on iOS")
    fun saveAddon(slug: String, files: Map<String, ByteArray>) = error("Not supported on iOS")
    fun loadAddons(): Map<String, Map<String, ByteArray>> = emptyMap()
    fun deleteAddon(slug: String) = Unit
}

@Composable
internal fun rememberZipAddonPicker(onPicked: (bytes: ByteArray, fileName: String) -> Unit): () -> Unit = {}
