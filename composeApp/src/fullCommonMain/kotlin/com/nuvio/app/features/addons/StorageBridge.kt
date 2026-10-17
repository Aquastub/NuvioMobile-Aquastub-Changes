package com.nuvio.app.features.addons

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.nuvio.app.features.plugins.runtime.host.HostModule
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `nuvio.storage.get/set`: a small private key/value store for each addon, so it can keep things like a
 * downloaded channel list between requests (every request runs in a fresh script).
 */
internal class StorageBridge(private val slug: String) : HostModule {
    override fun register(runtime: QuickJs) {
        runtime.asyncFunction("__storage_call") { args: Array<Any?> ->
            val operation = args.getOrNull(0)?.toString().orEmpty()
            val key = args.getOrNull(1)?.toString().orEmpty()
            val value = args.getOrNull(2)?.toString().orEmpty()
            runCatching {
                when (operation) {
                    "get" -> buildJsonObject {
                        put("ok", true)
                        put("value", ZipAddonPlatform.storageGet(slug, key))
                    }
                    "set" -> {
                        ZipAddonPlatform.storageSet(slug, key, value)
                        buildJsonObject { put("ok", true) }
                    }
                    else -> buildJsonObject {
                        put("ok", false)
                        put("error", "Unknown operation")
                    }
                }.toString()
            }.getOrElse { error ->
                buildJsonObject {
                    put("ok", false)
                    put("error", error.message ?: "Storage failed")
                }.toString()
            }
        }
    }
}
