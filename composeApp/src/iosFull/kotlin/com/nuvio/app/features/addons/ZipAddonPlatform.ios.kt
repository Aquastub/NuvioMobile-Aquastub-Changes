package com.nuvio.app.features.addons

import androidx.compose.runtime.Composable

// Installing addons from ADINST files is not implemented on iOS yet.
internal object ZipAddonPlatform {
    val supported: Boolean = false

    fun entryNames(path: String): Set<String> = error("Not supported on iOS")
    fun readSmallFiles(path: String): Map<String, ByteArray> = error("Not supported on iOS")
    fun installPackage(slug: String, path: String, prefix: String): Unit = error("Not supported on iOS")
    fun loadAddons(): Map<String, Map<String, ByteArray>> = emptyMap()
    fun deleteAddon(slug: String) = Unit
    fun loadSettings(slug: String): Map<String, String> = emptyMap()
    fun saveSettings(slug: String, values: Map<String, String>) = Unit
    fun storageGet(slug: String, key: String): String? = null
    fun storageSet(slug: String, key: String, value: String) = Unit
    fun discard(path: String) = Unit
}

@Composable
internal fun rememberZipAddonPicker(onPicked: (path: String, fileName: String) -> Unit): () -> Unit = {}
