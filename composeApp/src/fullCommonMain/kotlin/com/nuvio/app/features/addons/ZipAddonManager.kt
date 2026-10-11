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
 * Files may also sit inside a single top-level folder in the package.
 */
internal object ZipAddonManager {
    private val log = Logger.withTag("ZipAddonManager")
    private val json = Json { ignoreUnknownKeys = true }

    private const val MAX_ZIP_BYTES = 8 * 1024 * 1024
    private const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024
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

    suspend fun install(zipBytes: ByteArray): InstallResult = withContext(Dispatchers.Default) {
        if (!ZipAddonPlatform.supported) return@withContext InstallResult.Error("Not supported on this platform")
        if (zipBytes.isEmpty()) return@withContext InstallResult.Error("The file is empty")
        if (zipBytes.size > MAX_ZIP_BYTES) return@withContext InstallResult.Error("The ADINST is larger than 8 MB")

        val files = try {
            normalize(ZipAddonPlatform.unzip(zipBytes))
        } catch (error: Throwable) {
            return@withContext InstallResult.Error(error.message ?: "Not a valid ADINST file")
        }

        val (slug, addon) = try {
            prepare(files)
        } catch (error: IllegalArgumentException) {
            return@withContext InstallResult.Error(error.message ?: "Invalid addon")
        }

        val manifestUrl = "$LOCAL_ADDON_SCHEME$slug/manifest.json"
        val alreadyListed = AddonRepository.uiState.value.addons.any { it.manifestUrl == manifestUrl }

        try {
            ZipAddonPlatform.saveAddon(slug, files)
        } catch (error: Throwable) {
            return@withContext InstallResult.Error(error.message ?: "Could not save the addon")
        }
        LocalAddonRegistry.register(addon)

        if (alreadyListed) {
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
        return ScriptLocalAddon(
            slug = slug,
            displayName = manifest.string("name").orEmpty(),
            manifestJson = manifestText,
            code = script.decodeToString(),
        )
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

    /** Drops a single wrapping folder and directory entries so manifest.json ends up at the root. */
    private fun normalize(raw: Map<String, ByteArray>): Map<String, ByteArray> {
        val files = raw.filterKeys { it.isNotEmpty() && !it.endsWith("/") }
        if (files.containsKey("manifest.json")) return files
        val tops = files.keys.map { it.substringBefore('/', missingDelimiterValue = "") }.toSet()
        val single = tops.singleOrNull()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("manifest.json was not found in the ADINST")
        val prefix = "$single/"
        val stripped = files.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
        if (!stripped.containsKey("manifest.json")) {
            throw IllegalArgumentException("manifest.json was not found in the ADINST")
        }
        return stripped
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
