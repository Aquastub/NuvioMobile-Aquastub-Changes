package com.nuvio.app.features.addons

/** Device programs (recorders, ffmpeg) are Android-only for now. */
@Suppress("UNUSED_PARAMETER")
internal object AddonProgramPlatform {
    suspend fun call(slug: String, operation: String, payloadJson: String, programs: Map<String, Map<String, String>>): String =
        """{"ok":false,"error":"Programs are not available on iOS."}"""
}
