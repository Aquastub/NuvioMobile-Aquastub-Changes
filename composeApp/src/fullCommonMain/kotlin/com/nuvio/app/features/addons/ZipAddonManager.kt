package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Installs addons from an ADINST file (a ZIP renamed to .adinst):
 *
 *   my-addon.adinst
 *     manifest.json   Stremio manifest (id, name, version, resources, types, catalogs, ...)
 *                     optional "main": "index.js" (default)
 *     index.js        exposes handle(request), see [ScriptLocalAddon]
 *
 * Files may also sit inside a single top-level folder in the package. A package may also carry
 * programs (see [readPrograms]); those are unpacked to disk and run by the addon program host.
 */
internal object ZipAddonManager {
    private val log = Logger.withTag("ZipAddonManager")
    private val json = Json { ignoreUnknownKeys = true }

    private const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024
    private const val MAX_PLAYER_ACTIONS = 4
    private const val MAX_SETTINGS = 12
    private val SETTING_KEY_REGEX = Regex("[A-Za-z0-9_]{1,32}")
    private val SETTING_TYPES = setOf("text", "url", "password", "number")
    private val PROGRAM_ID_REGEX = Regex("[A-Za-z0-9._-]{1,32}")
    private const val DEFAULT_MAIN = "index.js"

    sealed interface InstallResult {
        data class Success(val name: String, val updated: Boolean) : InstallResult
        data class Error(val message: String) : InstallResult
    }

    /** Registers every installed ZIP addon. Call once at startup, before AddonRepository.initialize(). */
    fun loadInstalled() {
        if (!ZipAddonPlatform.supported) return
        runCatching { ZipAddonPlatform.loadAddons() }
            .onFailure { log.w { "Could not read installed addons: ${it.message}" } }
            .getOrDefault(emptyMap())
            .forEach { (slug, files) ->
                runCatching { LocalAddonRegistry.register(buildAddon(slug, files)) }
                    .onFailure { log.w { "Skipping addon $slug: ${it.message}" } }
            }
    }

    suspend fun install(packagePath: String): InstallResult = withContext(Dispatchers.Default) {
        try {
            installPackage(packagePath)
        } finally {
            ZipAddonPlatform.discard(packagePath)
        }
    }

    private suspend fun installPackage(packagePath: String): InstallResult {
        if (!ZipAddonPlatform.supported) return InstallResult.Error("Not supported on this platform")

        val names: Set<String>
        val prefix: String
        val files: Map<String, ByteArray>
        try {
            names = ZipAddonPlatform.entryNames(packagePath)
            prefix = rootPrefix(names)
            files = rekey(ZipAddonPlatform.readSmallFiles(packagePath), prefix)
        } catch (error: Throwable) {
            return InstallResult.Error(error.message ?: "Not a valid ADINST file")
        }

        val (slug, addon) = try {
            prepare(files)
        } catch (error: IllegalArgumentException) {
            return InstallResult.Error(error.message ?: "Invalid addon")
        }

        val manifestUrl = "$LOCAL_ADDON_SCHEME$slug/manifest.json"
        val alreadyListed = AddonRepository.uiState.value.addons.any { it.manifestUrl == manifestUrl }

        try {
            ZipAddonPlatform.installPackage(slug, packagePath, prefix)
        } catch (error: Throwable) {
            return InstallResult.Error(error.message ?: "Could not save the addon")
        }
        LocalAddonRegistry.register(addon)

        return if (alreadyListed) {
            AddonRepository.refreshAddon(manifestUrl, forceRefresh = true)
            InstallResult.Success(name = addon.displayName, updated = true)
        } else {
            when (val added = AddonRepository.addAddon(manifestUrl)) {
                is AddAddonResult.Success -> InstallResult.Success(name = added.manifest.name, updated = false)
                is AddAddonResult.Error -> {
                    LocalAddonRegistry.removeUserInstalled(manifestUrl)
                    InstallResult.Error(added.message)
                }
            }
        }
    }

    /** Validates the files and returns the slug plus the addon, or throws IllegalArgumentException. */
    private fun prepare(files: Map<String, ByteArray>): Pair<String, ScriptLocalAddon> {
        val slug = slugFor(files)
        val existing = LocalAddonRegistry.get(slug)
        require(existing == null || existing.removable) { "\"$slug\" is a built-in addon and cannot be replaced" }
        return slug to buildAddon(slug, files)
    }

    private fun buildAddon(slug: String, files: Map<String, ByteArray>): ScriptLocalAddon {
        val manifestText = files["manifest.json"]?.decodeToString()
            ?: throw IllegalArgumentException("manifest.json is missing")
        val manifest = parseManifest(manifestText)
        val mainPath = manifest.string("main")?.trim()?.removePrefix("./")?.ifBlank { null } ?: DEFAULT_MAIN
        val script = files[mainPath]
            ?: throw IllegalArgumentException("$mainPath is missing (set \"main\" in manifest.json)")
        require(script.size <= MAX_SCRIPT_BYTES) { "$mainPath is larger than 2 MB" }
        val permissions = readPermissions(manifest)
        return ScriptLocalAddon(
            slug = slug,
            displayName = manifest.string("name").orEmpty(),
            manifestJson = manifestText,
            code = script.decodeToString(),
            permissions = permissions,
            programs = if (PERMISSION_PROGRAMS in permissions) readPrograms(manifest) else emptyMap(),
            settings = readSettings(manifest),
            // Buttons only count when the addon asked for (and the user approved) the player permission.
            playerActions = if (PERMISSION_PLAYER_UI in permissions) readPlayerActions(manifest) else emptyList(),
        )
    }

    /** `"nuvio": { "permissions": ["programs", "playerUi"] }` in manifest.json. */
    private fun readPermissions(manifest: JsonObject): Set<String> =
        ((manifest["nuvio"] as? JsonObject)?.get("permissions") as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null } }
            .toSet()

    /** `"nuvio": { "playerActions": [{ "id": "record", "label": "Record", "match": "regex" }] }` */
    private fun readPlayerActions(manifest: JsonObject): List<AddonPlayerAction> =
        ((manifest["nuvio"] as? JsonObject)?.get("playerActions") as? JsonArray)
            .orEmpty()
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.string("id")?.trim()?.ifBlank { null } ?: return@mapNotNull null
                val label = obj.string("label")?.trim()?.ifBlank { null } ?: return@mapNotNull null
                AddonPlayerAction(id = id, label = label.take(24), match = obj.string("match")?.ifBlank { null })
            }
            .take(MAX_PLAYER_ACTIONS)

    /** `"nuvio": { "settings": [{ "key": "playlistUrl", "label": "Playlist URL", "type": "url" }] }` */
    private fun readSettings(manifest: JsonObject): List<AddonSetting> =
        ((manifest["nuvio"] as? JsonObject)?.get("settings") as? JsonArray)
            .orEmpty()
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val key = obj.string("key")?.trim()?.takeIf { SETTING_KEY_REGEX.matches(it) } ?: return@mapNotNull null
                val label = obj.string("label")?.trim()?.ifBlank { null } ?: key
                val type = obj.string("type")?.lowercase()?.takeIf { it in SETTING_TYPES } ?: "text"
                AddonSetting(key = key, label = label.take(60), type = type, hint = obj.string("hint")?.take(160))
            }
            .take(MAX_SETTINGS)

    /** What the confirmation dialog shows before anything is installed. */
    data class Inspection(val name: String, val permissions: Set<String>, val programs: List<String>)

    fun inspect(packagePath: String): Inspection? = runCatching {
        val names = ZipAddonPlatform.entryNames(packagePath)
        val files = rekey(ZipAddonPlatform.readSmallFiles(packagePath), rootPrefix(names))
        val manifest = parseManifest(files["manifest.json"]?.decodeToString().orEmpty())
        Inspection(
            name = manifest.string("name").orEmpty(),
            permissions = readPermissions(manifest),
            programs = readPrograms(manifest).keys.toList(),
        )
    }.getOrNull()

    /**
     * `"nuvio": { "programs": { "ffmpeg": { "arm64-v8a": "bin/arm64-v8a/ffmpeg", "armeabi-v7a": "bin/armeabi-v7a/ffmpeg" } } }`
     * Programs carried inside the package, per CPU type. A declared file that is missing is reported when
     * the addon tries to use it, so one package can work with or without the (large) binary.
     */
    private fun readPrograms(manifest: JsonObject): Map<String, Map<String, String>> {
        val declared = (manifest["nuvio"] as? JsonObject)?.get("programs") as? JsonObject ?: return emptyMap()
        val result = LinkedHashMap<String, Map<String, String>>()
        for ((id, value) in declared) {
            if (!PROGRAM_ID_REGEX.matches(id)) continue
            val perAbi = (value as? JsonObject)?.mapNotNull { (abi, path) ->
                val relative = (path as? JsonPrimitive)?.contentOrNull?.trim()?.removePrefix("./")?.ifBlank { null }
                    ?: return@mapNotNull null
                if (relative.startsWith("/") || relative.split('/').any { it == ".." }) null else abi to relative
            }?.toMap().orEmpty()
            if (perAbi.isNotEmpty()) result[id] = perAbi
        }
        return result
    }

    private fun parseManifest(text: String): JsonObject {
        val manifest = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw IllegalArgumentException("manifest.json is not valid JSON")
        require(!manifest.string("id").isNullOrBlank()) { "manifest.json needs an \"id\"" }
        require(!manifest.string("name").isNullOrBlank()) { "manifest.json needs a \"name\"" }
        require((manifest["resources"] as? JsonArray)?.isNotEmpty() == true) {
            "manifest.json needs a non-empty \"resources\" list"
        }
        return manifest
    }

    private fun slugFor(files: Map<String, ByteArray>): String {
        val manifest = parseManifest(
            files["manifest.json"]?.decodeToString() ?: throw IllegalArgumentException("manifest.json is missing"),
        )
        val id = manifest.string("id").orEmpty()
        val slug = id.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)
        require(slug.isNotEmpty()) { "manifest.json \"id\" must contain letters or digits" }
        return slug
    }

    /** The wrapping folder to strip (like "my-addon/") so manifest.json sits at the root; "" if it already does. */
    private fun rootPrefix(names: Collection<String>): String {
        if ("manifest.json" in names) return ""
        val tops = names.map { it.substringBefore('/', missingDelimiterValue = "") }.toSet()
        val single = tops.singleOrNull()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("manifest.json was not found in the ADINST")
        val prefix = "$single/"
        if ("${prefix}manifest.json" !in names) {
            throw IllegalArgumentException("manifest.json was not found in the ADINST")
        }
        return prefix
    }

    private fun rekey(raw: Map<String, ByteArray>, prefix: String): Map<String, ByteArray> =
        raw.filterKeys { it.isNotEmpty() && it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
