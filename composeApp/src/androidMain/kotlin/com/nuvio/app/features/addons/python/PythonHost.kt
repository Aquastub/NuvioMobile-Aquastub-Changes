package com.nuvio.app.features.addons.python

/** What an addon asks the Python runtime to run. Paths are absolute and already checked by the caller. */
data class PythonRunRequest(
    val runId: String,
    /** A .py file to run, or null when [code] is given. */
    val scriptPath: String?,
    val code: String?,
    val args: List<String>,
    /** Folders added to sys.path (the addon's own packages). */
    val paths: List<String>,
    val env: Map<String, String>,
    val stdin: String?,
    val maxOutputChars: Int,
)

data class PythonRunResult(val exitCode: Int, val stdout: String, val stderr: String)

/** The embedded Python interpreter. Implemented in the app module, where the Python plugin lives. */
interface PythonHost {
    /** The interpreter's version, for example "3.11.17". Starts the interpreter if needed. */
    val version: String?

    /** Runs to the end and returns what was printed. [onLine] receives each printed line as it happens. */
    fun run(request: PythonRunRequest, onLine: ((String) -> Unit)? = null): PythonRunResult

    /** Interrupts a run in progress (best effort: it ends the next time the script executes Python code). */
    fun stop(runId: String)
}

/** Set by the app at startup; null when this build has no Python. */
object PythonRuntime {
    @Volatile
    var host: PythonHost? = null
}
