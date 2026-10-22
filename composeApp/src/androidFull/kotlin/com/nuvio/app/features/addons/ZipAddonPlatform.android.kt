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
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Storage for installed ADINST addons. Packages can carry programs (ffmpeg, yt-dlp, ...), so they are
 * handled as files on disk and unpacked as a stream, never held in memory as a whole.
 *
 *   filesDir/local_addons/<slug>/        the unpacked package
 *   filesDir/local_addon_data/<slug>/    private working folder for the addon's programs
 */
internal object ZipAddonPlatform {
    private const val DIRECTORY = "local_addons"
    private const val DATA_DIRECTORY = "local_addon_data"
    private const val MAX_ENTRIES = 5000
    const val MAX_PACKAGE_BYTES = 300L * 1024 * 1024
    private const val MAX_FILE_BYTES = 250L * 1024 * 1024
    private const val SMALL_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_STORAGE_CHARS = 16 * 1024 * 1024

    private var root: File? = null
    private var dataRoot: File? = null

    val supported: Boolean get() = root != null

    fun initialize(context: Context) {
        root = File(context.filesDir, DIRECTORY).also { it.mkdirs() }
        dataRoot = File(context.filesDir, DATA_DIRECTORY).also { it.mkdirs() }
    }

    /** Names of every file in the package (safe, "/"-separated). */
    fun entryNames(path: String): Set<String> = ZipFile(File(path)).use { zip ->
        val names = LinkedHashSet<String>()
        for (entry in zip.entries()) {
            if (entry.isDirectory) continue
            require(names.size < MAX_ENTRIES) { "The ADINST has too many files" }
            names += safeName(entry.name)
        }
        require(names.isNotEmpty()) { "The file is empty or not a valid ADINST (ZIP) package" }
        names
    }

    /** The package's small files (manifest, scripts...), read into memory. Big files (programs) are skipped. */
    fun readSmallFiles(path: String): Map<String, ByteArray> = ZipFile(File(path)).use { zip ->
        val files = LinkedHashMap<String, ByteArray>()
        for (entry in zip.entries()) {
            if (entry.isDirectory || entry.size !in 0..SMALL_FILE_BYTES) continue
            val name = safeName(entry.name)
            if (!isScriptOrManifest(name)) continue // libraries (hundreds of .py files) are only unpacked
            files[name] = readBounded(zip, entry, SMALL_FILE_BYTES)
        }
        files
    }

    /**
     * Unpacks the whole package into the addon's folder as a stream, stripping [prefix] (a wrapping
     * folder). The old version is replaced only once everything has been written.
     */
    fun installPackage(slug: String, path: String, prefix: String) {
        val target = addonDir(slug)
        val base = checkNotNull(root) { "Addon storage is not initialised" }
        val staging = File(base, ".$slug.new").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        try {
            var total = 0L
            ZipFile(File(path)).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val name = safeName(entry.name)
                    if (!name.startsWith(prefix)) continue
                    val relative = name.removePrefix(prefix)
                    val out = File(staging, relative)
                    check(out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Unsafe path in ADINST" }
                    out.parentFile?.mkdirs()
                    var written = 0L
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                written += read
                                total += read
                                require(written <= MAX_FILE_BYTES) { "A file in the ADINST is too large" }
                                require(total <= MAX_PACKAGE_BYTES) { "The ADINST is too large when unpacked" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    out.setReadable(true, false)
                }
            }
            target.deleteRecursively()
            check(staging.renameTo(target)) { "Could not save the addon" }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    /** Small files of every installed addon (what the script runner needs). Programs stay on disk. */
    fun loadAddons(): Map<String, Map<String, ByteArray>> {
        val base = root ?: return emptyMap()
        return base.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }.orEmpty().associate { dir ->
            dir.name to dir.walkTopDown()
                .filter { it.isFile && it.length() <= SMALL_FILE_BYTES && isScriptOrManifest(it.name) }
                .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }
        }
    }

    fun deleteAddon(slug: String) {
        addonDir(slug).deleteRecursively()
        dataDir(slug).deleteRecursively()
    }

    /** Where the unpacked package lives (programs are found here). */
    fun addonDir(slug: String): File {
        val base = checkNotNull(root) { "Addon storage is not initialised" }
        require(slug.matches(Regex("[a-z0-9-]+"))) { "Invalid addon id" }
        return File(base, slug)
    }

    /** The addon's private working folder, kept when the addon is updated. */
    fun dataDir(slug: String): File {
        val base = checkNotNull(dataRoot) { "Addon storage is not initialised" }
        require(slug.matches(Regex("[a-z0-9-]+"))) { "Invalid addon id" }
        return File(base, slug).also { it.mkdirs() }
    }

    /** The user's values for the addon's settings (key -> text). */
    fun loadSettings(slug: String): Map<String, String> = runCatching {
        val file = File(dataDir(slug), "settings.json")
        if (!file.isFile) return@runCatching emptyMap<String, String>()
        val json = org.json.JSONObject(file.readText())
        json.keys().asSequence().associateWith { json.optString(it, "") }
    }.getOrDefault(emptyMap())

    fun saveSettings(slug: String, values: Map<String, String>) {
        val json = org.json.JSONObject()
        values.forEach { (key, value) -> json.put(key, value) }
        File(dataDir(slug), "settings.json").writeText(json.toString())
    }

    /** nuvio.storage: a small private key/value store per addon (cached lists, state...). */
    fun storageGet(slug: String, key: String): String? {
        val file = storageFile(slug, key)
        return if (file.isFile) file.readText() else null
    }

    fun storageSet(slug: String, key: String, value: String) {
        require(value.length <= MAX_STORAGE_CHARS) { "Value is too large" }
        storageFile(slug, key).writeText(value)
    }

    private fun storageFile(slug: String, key: String): File {
        require(key.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid storage key" }
        return File(File(dataDir(slug), "storage").also { it.mkdirs() }, key)
    }

    fun discard(path: String) {
        runCatching { File(path).delete() }
    }

    private fun readBounded(zip: ZipFile, entry: java.util.zip.ZipEntry, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        zip.getInputStream(entry).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit) { "A file in the ADINST is larger than it says" }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    /** Only the manifest and the addon's JavaScript are needed in memory; everything else stays on disk. */
    private fun isScriptOrManifest(name: String): Boolean = name.endsWith(".json") || name.endsWith(".js")

    /** Rejects absolute paths and "..", so a package cannot write outside its addon folder. */
    private fun safeName(raw: String): String {
        val name = raw.replace('\\', '/').trimStart('/')
        require(name.split('/').none { it == ".." }) { "The ADINST contains an unsafe path" }
        return name
    }
}

/** Copies the picked file to the cache (bounded) and hands back its path, so big packages never sit in memory. */
@Composable
internal fun rememberZipAddonPicker(onPicked: (path: String, fileName: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnPicked = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(context.cacheDir, "adinst-import.adinst")
                    target.delete()
                    var copied = 0L
                    val opened = context.contentResolver.openInputStream(uri) ?: return@runCatching null
                    opened.use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                copied += read
                                // One byte over the limit is enough for the installer to refuse it.
                                if (copied > ZipAddonPlatform.MAX_PACKAGE_BYTES + 1) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                    } ?: "addon.adinst"
                    if (copied > ZipAddonPlatform.MAX_PACKAGE_BYTES) {
                        target.delete()
                        null
                    } else {
                        target.absolutePath to name
                    }
                }.getOrNull()
            }
            picked?.let { (path, name) -> latestOnPicked.value(path, name) }
        }
    }
    return {
        // .adinst has no registered MIME type, so allow any file and check the extension on pick.
        launcher.launch(arrayOf("*/*"))
    }
}
