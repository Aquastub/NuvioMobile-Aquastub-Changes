package com.nuvio.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.nuvio.app.features.addons.python.PythonRuntime

/**
 * Hands the shared code the embedded Python (it needs the app module's Python plugin). A provider is
 * created before Application.onCreate, so this needs no Application subclass of its own. The interpreter
 * itself only starts the first time an addon uses it.
 */
class PythonHostInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        PythonRuntime.host = ChaquopyPythonHost(appContext)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
