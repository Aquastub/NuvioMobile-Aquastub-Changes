package com.nuvio.app.features.addons

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.program.AddonProgramService
import com.nuvio.app.features.addons.program.AddonProgramState
import com.nuvio.app.features.addons.program.HlsRecorder
import com.nuvio.app.features.addons.program.RawStreamRecorder
import com.nuvio.app.features.addons.python.PythonHost
import com.nuvio.app.features.addons.python.PythonRunRequest
import com.nuvio.app.features.addons.python.PythonRunResult
import com.nuvio.app.features.addons.python.PythonRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The dedicated host for what local addons run in the background.
 *
 * - Programs bundled in the addon (ffmpeg, yt-dlp...) declared under `nuvio.programs` in its manifest.
 * - Built-in recorders (`hls-record`, `stream-record`) that need no binary.
 *
 * Android does not let an app exec() a file it unpacked into its own storage, so a bundled program is
 * launched through the system linker (`/system/bin/linker64 <program> <args>`), the same way Termux does.
 * Programs must be Android (bionic) builds for the phone's CPU. They run with this app's access, which is
 * why the install dialog asks the user to approve the `programs` permission.
 */
internal object AddonProgramPlatform {
    private val log = Logger.withTag("AddonPrograms")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val programs = ConcurrentHashMap<String, RunningProgram>()
    private val counter = AtomicInteger(0)
    private var appContext: Context? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private class RunningProgram(
        val id: String,
        val slug: String,
        val kind: String,
        val name: String,
        val baseName: String,
        val tag: String,
        val startedAt: Long,
        val directory: File,
        val bundled: Boolean,
    ) {
        @Volatile var running = true
        @Volatile var error: String? = null
        @Volatile var abort: (() -> Unit)? = null
        val logLines = java.util.concurrent.ConcurrentLinkedDeque<String>()
        var job: Job? = null

        fun appendLog(line: String) {
            logLines.addLast(line.take(300))
            while (logLines.size > 20) logLines.pollFirst()
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        AddonProgramState.stopAll = { stopAll() }
    }

    suspend fun call(
        slug: String,
        operation: String,
        payloadJson: String,
        declared: Map<String, Map<String, String>>,
    ): String = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext failure("Programs are not ready yet.")
        val payload = runCatching { json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
        try {
            when (operation) {
                "python.available" -> pythonAvailable()
                "python.run" -> pythonRun(context, slug, payload)
                "python.start" -> pythonStart(context, slug, payload)
                "available" -> available(slug, declared)
                "start" -> start(context, slug, payload, declared)
                "exec" -> exec(context, slug, payload, declared)
                "stop" -> stop(slug, payload)
                "list" -> list(slug)
                else -> failure("Unknown operation \"$operation\".")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "Program call failed" }
            failure(error.message ?: "Something went wrong.")
        }
    }

    // -------------------------------------------------------- bundled programs

    private class Resolved(val file: File, val abi: String)

    /** Finds the program's file for this phone's CPU and checks that it really is a matching ELF binary. */
    private fun resolve(slug: String, id: String, declared: Map<String, Map<String, String>>): Result<Resolved> {
        val perAbi = declared[id] ?: return Result.failure(IllegalArgumentException("This addon has no program called \"$id\"."))
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in perAbi }
            ?: return Result.failure(
                IllegalArgumentException("\"$id\" is not built for this phone (${Build.SUPPORTED_ABIS.joinToString()})."),
            )
        val addonDir = ZipAddonPlatform.addonDir(slug)
        val file = File(addonDir, perAbi.getValue(abi))
        if (!file.canonicalPath.startsWith(addonDir.canonicalPath + File.separator)) {
            return Result.failure(IllegalArgumentException("The path of \"$id\" is not allowed."))
        }
        if (!file.isFile) return Result.failure(IllegalArgumentException("The file for \"$id\" is missing from the addon."))
        elfProblem(file, abi)?.let { return Result.failure(IllegalArgumentException("\"$id\": $it")) }
        return Result.success(Resolved(file, abi))
    }

    private fun elfProblem(file: File, abi: String): String? {
        val header = ByteArray(20)
        val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
        if (read < 20) return "the file is too short to be a program."
        if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) {
            return "the file is not a program (ELF) for Android."
        }
        val is64 = header[4].toInt() == 2
        val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
        val expected = when (abi) {
            "arm64-v8a" -> 183 to true
            "armeabi-v7a" -> 40 to false
            "x86_64" -> 62 to true
            "x86" -> 3 to false
            else -> return "unsupported CPU type $abi."
        }
        return if (machine != expected.first || is64 != expected.second) "the file was built for a different CPU than $abi." else null
    }

    private fun available(slug: String, declared: Map<String, Map<String, String>>): String = success {
        put(
            "programs",
            buildJsonArray {
                declared.keys.forEach { id ->
                    val result = resolve(slug, id, declared)
                    add(
                        buildJsonObject {
                            put("id", id)
                            put("usable", result.isSuccess)
                            result.exceptionOrNull()?.message?.let { put("reason", it) }
                        },
                    )
                }
            },
        )
    }

    /** `{recordings}`, `{data}` and `{tmp}` in arguments become real folders. */
    private fun expand(value: String, recordings: File, data: File): String =
        value.replace("{recordings}", recordings.absolutePath)
            .replace("{data}", data.absolutePath)
            .replace("{tmp}", File(data, "tmp").also { it.mkdirs() }.absolutePath)

    private fun processBuilder(
        resolved: Resolved,
        args: List<String>,
        env: Map<String, String>,
        slug: String,
    ): ProcessBuilder {
        val linker = if (resolved.abi.contains("64")) "/system/bin/linker64" else "/system/bin/linker"
        val data = ZipAddonPlatform.dataDir(slug)
        val builder = ProcessBuilder(listOf(linker, resolved.file.absolutePath) + args)
        builder.directory(data)
        builder.redirectErrorStream(false)
        val environment = builder.environment()
        val programDir = resolved.file.parentFile
        environment["LD_LIBRARY_PATH"] = listOfNotNull(programDir?.absolutePath, programDir?.let { File(it, "lib").absolutePath })
            .joinToString(":")
        environment["HOME"] = data.absolutePath
        environment["TMPDIR"] = File(data, "tmp").also { it.mkdirs() }.absolutePath
        env.forEach { (key, value) -> environment[key] = value }
        return builder
    }

    private fun readEnv(spec: JsonObject, recordings: File, data: File): Map<String, String> {
        val raw = spec["env"] as? JsonObject ?: return emptyMap()
        return raw.mapNotNull { (key, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!ENV_NAME_REGEX.matches(key) || key == "LD_PRELOAD" || key == "LD_LIBRARY_PATH") return@mapNotNull null
            key to expand(text.take(1_000), recordings, data)
        }.toMap()
    }

    private fun readArgs(spec: JsonObject, recordings: File, data: File): List<String>? {
        val args = (spec["args"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull ?: return null }
        if (args.size > 300 || args.any { it.length > 8_000 }) return null
        return args.map { expand(it, recordings, data) }
    }

    /** Runs a bundled program to completion and returns what it printed (for example `yt-dlp -J <url>`). */
    private suspend fun exec(
        context: Context,
        slug: String,
        spec: JsonObject,
        declared: Map<String, Map<String, String>>,
    ): String {
        val id = spec.string("program").orEmpty()
        val resolved = resolve(slug, id, declared).getOrElse { return failure(it.message ?: "Program not available.") }
        val recordings = recordingsDirectory(context)
        val data = ZipAddonPlatform.dataDir(slug)
        val args = readArgs(spec, recordings, data) ?: return failure("\"args\" must be a list of at most 300 short strings.")
        val timeoutMs = (spec.int("timeoutMs") ?: 30_000).coerceIn(1_000, 600_000).toLong()
        val maxOutput = (spec.int("maxOutputBytes") ?: 1_000_000).coerceIn(1_000, 8_000_000)

        val process = processBuilder(resolved, args, readEnv(spec, recordings, data), slug).start()
        runCatching { process.outputStream.close() }
        val stdout = java.io.ByteArrayOutputStream()
        val stderr = java.io.ByteArrayOutputStream()
        val readers = listOf(
            thread(isDaemon = true) { copyLimited(process.inputStream, stdout, maxOutput) },
            thread(isDaemon = true) { copyLimited(process.errorStream, stderr, 64_000) },
        )
        var timedOut = false
        try {
            val finished = withContext(Dispatchers.IO) { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
            if (!finished) {
                timedOut = true
                process.destroyForcibly()
            }
        } catch (cancel: CancellationException) {
            process.destroyForcibly()
            throw cancel
        }
        readers.forEach { it.join(2_000) }
        return success {
            put("exitCode", if (timedOut) -1 else process.exitValue())
            put("timedOut", timedOut)
            put("stdout", stdout.toString(Charsets.UTF_8.name()))
            put("stderr", stderr.toString(Charsets.UTF_8.name()).takeLast(4_000))
        }
    }

    private fun copyLimited(input: java.io.InputStream, output: java.io.ByteArrayOutputStream, limit: Int) {
        runCatching {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val room = limit - output.size()
                if (room > 0) output.write(buffer, 0, minOf(read, room))
            }
        }
    }

    // ------------------------------------------------------------------ start

    private fun start(
        context: Context,
        slug: String,
        spec: JsonObject,
        declared: Map<String, Map<String, String>>,
    ): String {
        val requested = spec.string("program")?.trim().orEmpty().ifBlank { "auto" }
        val builtIn = requested in BUILT_IN_PROGRAMS
        val input = spec.string("input")?.trim().orEmpty()
        if (builtIn && !input.startsWith("http://", ignoreCase = true) && !input.startsWith("https://", ignoreCase = true)) {
            return failure("\"input\" must be an http(s) URL.")
        }

        val tag = spec.string("tag")?.take(500) ?: input.ifBlank { requested }
        programs.values.firstOrNull { it.slug == slug && it.running && it.tag == tag }?.let { existing ->
            return success {
                put("id", existing.id)
                put("already", true)
            }
        }
        if (programs.values.count { it.slug == slug && it.running } >= MAX_PER_ADDON) {
            return failure("Too many programs are already running for this addon.")
        }

        val kind = when {
            requested == "auto" -> if (input.substringBefore('?').contains(".m3u8", ignoreCase = true)) "hls-record" else "stream-record"
            builtIn -> requested
            else -> requested
        }

        val recordings = recordingsDirectory(context)
        val data = ZipAddonPlatform.dataDir(slug)
        var resolved: Resolved? = null
        var args = emptyList<String>()
        var env = emptyMap<String, String>()
        if (!builtIn && requested != "auto") {
            resolved = resolve(slug, requested, declared).getOrElse { return failure(it.message ?: "Program not available.") }
            args = readArgs(spec, recordings, data) ?: return failure("\"args\" must be a list of at most 300 short strings.")
            env = readEnv(spec, recordings, data)
        }

        val userAgent = spec.string("userAgent")?.takeIf { USER_AGENT_REGEX.matches(it) } ?: DEFAULT_USER_AGENT
        val segmentMinutes = (spec.int("segmentMinutes") ?: 30).coerceIn(1, 240)
        val maxMinutes = (spec.int("maxMinutes") ?: 360).coerceIn(1, 1_440)
        val name = spec.string("name")?.trim().orEmpty().ifBlank { "Recording" }
        val baseName = name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().take(60).ifBlank { "Recording" }

        // Final copies: the launched job below must not depend on variables that could change.
        val bundledProgram: Resolved? = resolved
        val bundledArgs: List<String> = args
        val bundledEnv: Map<String, String> = env

        val program = RunningProgram(
            id = "$slug-${counter.incrementAndGet()}",
            slug = slug,
            kind = kind,
            name = name,
            baseName = baseName,
            tag = tag,
            startedAt = System.currentTimeMillis(),
            directory = recordings,
            bundled = bundledProgram != null,
        )
        programs[program.id] = program
        prune(slug)

        AddonProgramState.activeCount.update { it + 1 }
        startService(context)

        program.job = scope.launch {
            try {
                val segmentMillis = segmentMinutes * 60_000L
                val maxMillis = maxMinutes * 60_000L
                val onLog: (String) -> Unit = { line -> program.appendLog(line) }
                when {
                    bundledProgram != null -> runBundled(program, processBuilder(bundledProgram, bundledArgs, bundledEnv, slug), maxMillis)
                    kind == "hls-record" -> {
                        val recorder = HlsRecorder(client, input, userAgent, recordings, baseName, segmentMillis, maxMillis, onLog)
                        program.abort = { recorder.abort() }
                        recorder.run()
                    }
                    else -> {
                        val recorder = RawStreamRecorder(client, input, userAgent, recordings, baseName, segmentMillis, maxMillis, onLog)
                        program.abort = { recorder.abort() }
                        recorder.run()
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                program.error = error.message ?: error.toString()
                log.w(error) { "Program ${program.id} failed" }
            } finally {
                program.running = false
                program.abort = null
                AddonProgramState.activeCount.update { (it - 1).coerceAtLeast(0) }
            }
        }

        return success {
            put("id", program.id)
            put("program", if (bundledProgram != null) requested else kind)
            put("directory", recordings.absolutePath)
        }
    }

    /** A long-running bundled program (a recorder, a local server...). Stopped on request or after maxMillis. */
    private suspend fun runBundled(program: RunningProgram, builder: ProcessBuilder, maxMillis: Long) {
        val process = builder.start()
        program.abort = { process.destroy() }
        val readers = listOf(process.inputStream, process.errorStream).map { stream ->
            thread(isDaemon = true, name = "program-log-${program.id}") {
                runCatching { stream.bufferedReader().forEachLine { line -> if (line.isNotBlank()) program.appendLog(line) } }
            }
        }
        val startedAt = System.currentTimeMillis()
        try {
            while (process.isAlive) {
                if (maxMillis > 0 && System.currentTimeMillis() - startedAt >= maxMillis) {
                    program.appendLog("Stopped: reached the time limit")
                    break
                }
                delay(500)
            }
            if (!process.isAlive) {
                readers.forEach { it.join(1_000) }
                val exit = process.exitValue()
                if (exit != 0) program.error = "Exited with code $exit: ${program.logLines.lastOrNull().orEmpty()}"
            }
        } finally {
            if (process.isAlive) {
                withContext(NonCancellable + Dispatchers.IO) {
                    // "q" lets programs like ffmpeg finish their file; then make sure the process is gone.
                    runCatching {
                        process.outputStream.write("q\n".toByteArray())
                        process.outputStream.flush()
                    }
                    if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly()
                }
            }
        }
    }

    // ------------------------------------------------------------------ python

    private fun pythonAvailable(): String = success {
        val host = PythonRuntime.host
        put("available", host != null)
        host?.version?.let { put("version", it) }
    }

    /** One checked Python request, ready to hand to the interpreter. */
    private class PythonJob(val host: PythonHost, val request: PythonRunRequest)

    private fun pythonJob(context: Context, slug: String, spec: JsonObject, runId: String): Result<PythonJob> {
        val host = PythonRuntime.host
            ?: return Result.failure(IllegalStateException("Python is not available in this build."))
        val addonDir = ZipAddonPlatform.addonDir(slug)
        val data = ZipAddonPlatform.dataDir(slug)
        val recordings = recordingsDirectory(context)

        val script = spec.string("script")?.trim()?.ifBlank { null }
        val code = spec.string("code")?.takeIf { it.isNotBlank() }
        if ((script == null) == (code == null)) {
            return Result.failure(IllegalArgumentException("Give either \"script\" (a .py file in the addon) or \"code\"."))
        }
        if (code != null && code.length > MAX_PYTHON_CODE_CHARS) {
            return Result.failure(IllegalArgumentException("\"code\" is too long. Put it in a .py file in the addon."))
        }
        var scriptFile: File? = null
        if (script != null) {
            scriptFile = File(addonDir, script)
            if (!scriptFile.canonicalPath.startsWith(addonDir.canonicalPath + File.separator) || !scriptFile.isFile) {
                return Result.failure(IllegalArgumentException("The script \"$script\" was not found in the addon."))
            }
        }
        val args = readArgs(spec, recordings, data)
            ?: return Result.failure(IllegalArgumentException("\"args\" must be a list of at most 300 short strings."))

        // Folders the script can import from: its own folder, the addon root and the usual library folders.
        val paths = buildList {
            scriptFile?.parentFile?.let { add(it.absolutePath) }
            add(addonDir.absolutePath)
            listOf("lib", "python", "site-packages").map { File(addonDir, it) }.filter { it.isDirectory }.forEach { add(it.absolutePath) }
        }.distinct()

        val env = buildMap {
            put("NUVIO_ADDON", addonDir.absolutePath)
            put("NUVIO_DATA", data.absolutePath)
            put("NUVIO_RECORDINGS", recordings.absolutePath)
            put("HOME", data.absolutePath)
            put("TMPDIR", File(data, "tmp").also { it.mkdirs() }.absolutePath)
            putAll(readEnv(spec, recordings, data).filterKeys { !it.startsWith("PYTHON") })
        }
        val maxOutput = (spec.int("maxOutputChars") ?: 1_000_000).coerceIn(1_000, 8_000_000)
        return Result.success(
            PythonJob(
                host,
                PythonRunRequest(
                    runId = runId,
                    scriptPath = scriptFile?.absolutePath,
                    code = code,
                    args = args,
                    paths = paths,
                    env = env,
                    stdin = spec.string("stdin")?.take(1_000_000),
                    maxOutputChars = maxOutput,
                ),
            ),
        )
    }

    /** Runs Python code or a script to the end and returns what it printed (stops it after timeoutMs). */
    private suspend fun pythonRun(context: Context, slug: String, spec: JsonObject): String {
        val runId = "py-${counter.incrementAndGet()}"
        val job = pythonJob(context, slug, spec, runId).getOrElse { return failure(it.message ?: "Python is not available.") }
        val timeoutMs = (spec.int("timeoutMs") ?: 60_000).coerceIn(1_000, 600_000).toLong()

        var result: PythonRunResult? = null
        var error: Throwable? = null
        val worker = thread(isDaemon = true, name = "python-$runId") {
            runCatching { job.host.run(job.request) }.onSuccess { result = it }.onFailure { error = it }
        }
        var timedOut = false
        try {
            runInterruptible(Dispatchers.IO) { worker.join(timeoutMs) }
            if (worker.isAlive) {
                timedOut = true
                job.host.stop(runId)
                runInterruptible(Dispatchers.IO) { worker.join(3_000) }
            }
        } finally {
            if (worker.isAlive) job.host.stop(runId) // cancelled while waiting
        }

        error?.let { return failure(it.message ?: "Python failed to start.") }
        val outcome = result ?: return failure("The script did not finish in time and could not be stopped.")
        return success {
            put("exitCode", outcome.exitCode)
            put("timedOut", timedOut)
            put("stdout", outcome.stdout)
            put("stderr", outcome.stderr.takeLast(8_000))
        }
    }

    /** Runs Python in the background (it shows up in list() and can be stopped with stop()). */
    private fun pythonStart(context: Context, slug: String, spec: JsonObject): String {
        val tag = spec.string("tag")?.take(500) ?: spec.string("script") ?: "python"
        programs.values.firstOrNull { it.slug == slug && it.running && it.tag == tag }?.let { existing ->
            return success {
                put("id", existing.id)
                put("already", true)
            }
        }
        if (programs.values.count { it.slug == slug && it.running } >= MAX_PER_ADDON) {
            return failure("Too many programs are already running for this addon.")
        }

        val id = "$slug-${counter.incrementAndGet()}"
        val job = pythonJob(context, slug, spec, id).getOrElse { return failure(it.message ?: "Python is not available.") }
        val maxMillis = (spec.int("maxMinutes") ?: 360).coerceIn(1, 1_440) * 60_000L
        val name = spec.string("name")?.trim().orEmpty().ifBlank { "Python" }

        val program = RunningProgram(
            id = id,
            slug = slug,
            kind = "python",
            name = name,
            baseName = name,
            tag = tag,
            startedAt = System.currentTimeMillis(),
            directory = recordingsDirectory(context),
            bundled = true,
        )
        programs[program.id] = program
        prune(slug)

        AddonProgramState.activeCount.update { it + 1 }
        startService(context)

        program.job = scope.launch {
            var result: PythonRunResult? = null
            var failed: Throwable? = null
            val worker = thread(isDaemon = true, name = "python-${program.id}") {
                runCatching { job.host.run(job.request) { line -> program.appendLog(line) } }
                    .onSuccess { result = it }
                    .onFailure { failed = it }
            }
            program.abort = { job.host.stop(program.id) }
            val startedAt = System.currentTimeMillis()
            try {
                while (worker.isAlive) {
                    if (System.currentTimeMillis() - startedAt >= maxMillis) {
                        program.appendLog("Stopped: reached the time limit")
                        job.host.stop(program.id)
                        break
                    }
                    delay(500)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } finally {
                if (worker.isAlive) {
                    job.host.stop(program.id)
                    withContext(NonCancellable + Dispatchers.IO) { worker.join(5_000) }
                }
                failed?.let { program.error = it.message ?: it.toString() }
                result?.let { done ->
                    if (done.exitCode != 0 && program.error == null) {
                        program.error = "Exited with code ${done.exitCode}: ${done.stderr.lines().lastOrNull { it.isNotBlank() }.orEmpty()}"
                    }
                }
                program.running = false
                program.abort = null
                AddonProgramState.activeCount.update { (it - 1).coerceAtLeast(0) }
            }
        }
        return success {
            put("id", program.id)
            put("program", "python")
        }
    }

    // -------------------------------------------------------------- stop/list

    private fun stop(slug: String, payload: JsonObject): String {
        val id = payload.string("id").orEmpty()
        val program = programs[id]?.takeIf { it.slug == slug } ?: return failure("No such program.")
        program.abort?.invoke()
        program.job?.cancel()
        return success { put("id", program.id) }
    }

    private fun stopAll() {
        programs.values.filter { it.running }.forEach { program ->
            program.abort?.invoke()
            program.job?.cancel()
        }
    }

    private fun list(slug: String): String = success {
        put(
            "programs",
            buildJsonArray {
                programs.values.filter { it.slug == slug }.sortedBy { it.startedAt }.forEach { program ->
                    add(
                        buildJsonObject {
                            put("id", program.id)
                            put("program", program.kind)
                            put("name", program.name)
                            put("tag", program.tag)
                            put("running", program.running)
                            put("startedAt", program.startedAt)
                            program.error?.let { put("error", it) }
                            if (program.logLines.isNotEmpty()) put("log", program.logLines.joinToString("\n").takeLast(2_000))
                            if (!program.bundled) {
                                put("files", JsonArray(recordedFiles(program).map { JsonPrimitive(it.absolutePath) }))
                            }
                        },
                    )
                }
            },
        )
    }

    private fun recordedFiles(program: RunningProgram): List<File> =
        program.directory.listFiles { file ->
            file.isFile && file.name.startsWith("${program.baseName}-") && file.lastModified() >= program.startedAt - 2_000
        }?.sortedBy { it.name }.orEmpty()

    /** Keeps the list short: only the 20 most recent programs per addon are remembered. */
    private fun prune(slug: String) {
        val finished = programs.values.filter { it.slug == slug && !it.running }.sortedBy { it.startedAt }
        finished.dropLast(20).forEach { programs.remove(it.id) }
    }

    // ---------------------------------------------------------------- helpers

    private fun recordingsDirectory(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(base, "NuvioRecordings").also { it.mkdirs() }
    }

    private fun startService(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, AddonProgramService::class.java))
        }.onFailure { log.w(it) { "Could not start the foreground service; the program still runs while the app is open" } }
    }

    private fun success(block: JsonObjectBuilder.() -> Unit = {}): String =
        buildJsonObject {
            put("ok", true)
            block()
        }.toString()

    private fun failure(message: String): String =
        buildJsonObject {
            put("ok", false)
            put("error", message)
        }.toString()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private const val MAX_PER_ADDON = 4
    private const val MAX_PYTHON_CODE_CHARS = 200_000
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
    private val USER_AGENT_REGEX = Regex("^[\\x20-\\x7E]{1,200}$")
    private val ENV_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    private val BUILT_IN_PROGRAMS = setOf("auto", "hls-record", "stream-record")
}
