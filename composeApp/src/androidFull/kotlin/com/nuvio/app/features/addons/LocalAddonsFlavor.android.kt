package com.nuvio.app.features.addons

import android.content.Context

/** Local addons and addon-import UI that only ship in the full distribution. */
internal fun registerFlavorLocalAddons(context: Context) {
    ZipAddonPlatform.initialize(context)
    ZipAddonManager.loadInstalled()
    LocalAddonUi.importContent = { ZipAddonImportSection() }
}
