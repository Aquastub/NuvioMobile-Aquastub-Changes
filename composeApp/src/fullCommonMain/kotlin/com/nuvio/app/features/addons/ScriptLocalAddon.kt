package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.features.plugins.runtime.PLUGIN_TIMEOUT_MS
import com.nuvio.app.features.plugins.runtime.crypto.CryptoBridge
import com.nuvio.app.features.plugins.runtime.host.HostApiRegistry
import com.nuvio.app.features.plugins.runtime.host.HostFunctions
import com.nuvio.app.features.plugins.runtime.js.JsBindings
import com.nuvio.app.features.plugins.runtime.js.JsRuntime
import com.nuvio.app.features.plugins.runtime.network.FetchBridge
import com.nuvio.app.features.plugins.runtime.network.UrlBridge
import com.nuvio.app.features.plugins.runtime.pluginDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A local addon whose logic is a JavaScript file shipped inside an installed ZIP.
 *
 * The script runs in the same sandboxed QuickJS runtime as plugins (fetch, URL, crypto, timers)
 * and must expose `handle(request)`:
 *
 *   module.exports.handle = async ({ resource, type, id, extra }) => ({ metas: [...] })
 *
 * It returns the normal Stremio response object (or a JSON string).
 */
internal class ScriptLocalAddon(
    override val slug: String,
    val displayName: String,
    private val manifestJson: String,
    private val code: String,
) : LocalAddon {
    private val log = Logger.withTag("ScriptLocalAddon")

    override val removable: Boolean = true

    override suspend fun manifest(): String = manifestJson

    override fun onRemoved() {
        ZipAddonPlatform.deleteAddon(slug)
    }

    override suspend fun handle(request: LocalAddonRequest): String {
        val requestJson = JsonObject(
            mapOf(
                "resource" to JsonPrimitive(request.resource),
                "type" to JsonPrimitive(request.type),
                "id" to JsonPrimitive(request.id),
                "extra" to JsonObject(request.extra.mapValues { JsonPrimitive(it.value) }),
            ),
        ).toString()

        return withContext(pluginDispatcher) {
            withTimeout(PLUGIN_TIMEOUT_MS) { run(requestJson) }
        }
    }

    private suspend fun run(requestJson: String): String {
        val jsRuntime = JsRuntime()
        val deferred = CompletableDeferred<String>()
        val hostRegistry = HostApiRegistry().apply {
            addModule(
                HostFunctions(
                    scraperId = "addon:$slug",
                    scraperSettingsJson = "{}",
                    callArgsJson = requestJson,
                    onResult = { deferred.complete(it) },
                ),
            )
            addModule(FetchBridge())
            addModule(UrlBridge())
            addModule(CryptoBridge())
        }

        return try {
            jsRuntime.use {
                hostRegistry.registerAll(this)
                val polyfill = runCatching { JsRuntime.polyfillBytecode(this) }.getOrNull()
                if (polyfill != null) evaluate<Any?>(polyfill) else evaluate<Any?>(JsBindings.staticPolyfillCode)
                evaluate<Any?>(wrapModule(code))
                evaluate<Any?>(CALL_CODE)
                deferred.await()
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.e(error) { "Addon $slug failed" }
            "{}"
        }
    }

    private fun wrapModule(source: String): String = """
        var module = { exports: {} };
        var exports = module.exports;
        (function() {
            $source
        })();
    """.trimIndent()

    private companion object {
        val CALL_CODE = """
            (async function() {
                try {
                    var handle = (module.exports && module.exports.handle) || globalThis.handle;
                    if (typeof handle !== 'function') {
                        console.error('handle() not found on module.exports or globalThis');
                        __capture_result('{}');
                        return;
                    }
                    var request = JSON.parse(__get_call_args());
                    var result = await handle(request);
                    __capture_result(typeof result === 'string' ? result : JSON.stringify(result == null ? {} : result));
                } catch (e) {
                    console.error('addon error:', e && e.message ? e.message : e, e && e.stack ? e.stack : '');
                    __capture_result('{}');
                }
            })();
        """.trimIndent()
    }
}
