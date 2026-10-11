package com.nuvio.app.features.addons

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

internal object ZipAddonPlatform {
    private const val DIRECTORY = "local_addons"
    private const val MAX_ENTRIES = 500
    private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024

    private var root: File? = null

    val supported: Boolean get() = root != null

    fun initialize(context: Context) {
        root = File(context.filesDir, DIRECTORY).also { it.mkdirs() }
    }

    fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = safeName(entry.name)
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                require(files.size < MAX_ENTRIES) { "The ADINST has too many files" }
                // Bounded read: a small ZIP can expand to gigabytes, so stop as soon as the limit is hit.
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_TOTAL_BYTES) { "The ADINST is too large when unpacked" }
                    out.write(buffer, 0, read)
                }
                files[name] = out.toByteArray()
                zip.closeEntry()
            }
        }
        require(files.isNotEmpty()) { "The file is empty or not a valid ADINST (ZIP) package" }
        return files
    }

    fun saveAddon(slug: String, files: Map<String, ByteArray>) {
        val dir = addonDir(slug)
        dir.deleteRecursively()
        files.forEach { (name, data) ->
            val target = File(dir, safeName(name))
            check(target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "Unsafe path in ADINST" }
            target.parentFile?.mkdirs()
            target.writeBytes(data)
        }
    }

    fun loadAddons(): Map<String, Map<String, ByteArray>> {
        val base = root ?: return emptyMap()
        return base.listFiles { file -> file.isDirectory }.orEmpty().associate { dir ->
            dir.name to dir.walkTopDown()
                .filter { it.isFile }
                .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }
        }
    }

    fun deleteAddon(slug: String) {
        addonDir(slug).deleteRecursively()
    }

    private fun addonDir(slug: String): File {
        val base = checkNotNull(root) { "Addon storage is not initialised" }
        require(slug.matches(Regex("[a-z0-9-]+"))) { "Invalid addon id" }
        return File(base, slug)
    }

    /** Rejects absolute paths and ".." so a ZIP cannot write outside its addon folder. */
    private fun safeName(raw: String): String {
        val name = raw.replace('\\', '/').trimStart('/')
        require(name.split('/').none { it == ".." }) { "The ADINST contains an unsafe path" }
        return name
    }
}

@Composable
internal fun rememberZipAddonPicker(onPicked: (bytes: ByteArray, fileName: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnPicked = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    // Never read more than the install limit (+1 byte so the manager can reject it).
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val limit = 8 * 1024 * 1024 + 1
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (out.size() < limit) {
                            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    } ?: return@runCatching null
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                    } ?: "addon.adinst"
                    bytes to name
                }.getOrNull()
            }
            picked?.let { (bytes, name) -> latestOnPicked.value(bytes, name) }
        }
    }
    return {
        // .adinst has no registered MIME type, so allow any file and check the extension on pick.
        launcher.launch(arrayOf("*/*"))
    }
}
