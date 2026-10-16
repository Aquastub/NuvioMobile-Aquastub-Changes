package com.nuvio.app.features.addons

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.nuvio.app.features.plugins.runtime.host.HostModule

/**
 * Lets an addon's script run the programs bundled in its package (ffmpeg, yt-dlp...) and the built-in
 * recorders through `nuvio.programs`.
 * Refuses everything unless the addon declared the "programs" permission, which the user approved at install.
 */
internal class ProgramBridge(
    private val slug: String,
    private val permissions: Set<String>,
    private val programs: Map<String, Map<String, String>>,
) : HostModule {
    override fun register(runtime: QuickJs) {
        runtime.asyncFunction("__program_call") { args: Array<Any?> ->
            val operation = args.getOrNull(0)?.toString().orEmpty()
            val payload = args.getOrNull(1)?.toString() ?: "{}"
            if (PERMISSION_PROGRAMS !in permissions) {
                """{"ok":false,"error":"This addon was not given the programs permission."}"""
            } else {
                AddonProgramPlatform.call(slug, operation, payload, programs)
            }
        }
    }
}

internal const val PERMISSION_PROGRAMS = "programs"
internal const val PERMISSION_PLAYER_UI = "playerUi"
