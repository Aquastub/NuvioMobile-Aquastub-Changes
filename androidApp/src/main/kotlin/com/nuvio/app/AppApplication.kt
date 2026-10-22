package com.nuvio.app

import com.nuvio.app.features.addons.python.PythonRuntime

/** The app's Application: the shared one, plus the Python host (it needs the app module's Python plugin). */
class AppApplication : NuvioApplication() {
    override fun onCreate() {
        super.onCreate()
        PythonRuntime.host = ChaquopyPythonHost(this)
    }
}
