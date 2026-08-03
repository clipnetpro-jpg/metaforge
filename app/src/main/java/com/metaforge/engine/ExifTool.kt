package com.metaforge.engine

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real ExifTool, running on device.
 *
 * Preferred mode is a long lived `-stay_open` daemon: Perl is started once and
 * each command is written to its stdin, which brings a round trip down to a few
 * tens of milliseconds instead of a second or two of interpreter start-up.
 *
 * The daemon is not assumed to work. It is probed at start-up, watched for
 * death, restarted once, and if it still refuses the engine falls back to
 * one-shot invocations. One-shot is slower but functionally identical, so a
 * fussy device degrades in speed rather than losing features.
 */
class ExifTool private constructor(private val context: Context) {

    data class Result(val stdout: String, val stderr: String, val ok: Boolean)

    enum class Mode { DAEMON, ONE_SHOT }

    /** Which path commands are currently taking, for the diagnostics screen. */
    @Volatile
    var mode: Mode = Mode.ONE_SHOT
        private set

    @Volatile
    var startupError: String? = null
        private set

    private var daemon: Daemon? = null
    private val lock = Any()
    private var restarts = 0

    // ------------------------------------------------------------------ daemon

    private class Daemon(
        val process: Process,
        val stdin: BufferedWriter,
        val stdout: BufferedReader,
        val stderr: BufferedReader,
    ) {
        val seq = AtomicInteger(0)

        val alive: Boolean
            get() = runCatching { process.exitValue(); false }.getOrDefault(true)

        fun exec(args: Array<out String>): Result {
            val n = seq.incrementAndGet()
            args.forEach { stdin.write(it); stdin.write("\n") }
            stdin.write("-execute$n\n")
            stdin.flush()

            val out = StringBuilder()
            val marker = "{ready$n}"
            while (true) {
                val line = stdout.readLine() ?: return Result(out.toString(), "engine closed", false)
                if (line.trim() == marker) break
                out.appendLine(line)
            }
            val err = StringBuilder()
            while (stderr.ready()) {
                val line = stderr.readLine() ?: break
                err.appendLine(line)
            }
            return Result(out.toString().trim(), err.toString().trim(), true)
        }

        fun close() {
            runCatching { stdin.write("-stay_open\nFalse\n"); stdin.flush() }
            runCatching { process.destroy() }
        }
    }

    private fun startDaemon(): Daemon? {
        val cmd = mutableListOf(PerlRuntime.perlBinary.absolutePath)
        cmd += PerlRuntime.includeArgs()
        // Canonical stay_open invocation. Nothing else: extra start-up options
        // such as -common_args make ExifTool exit before it reads stdin, which
        // is exactly how this silently broke the first time.
        cmd += listOf(PerlRuntime.exifToolScript.absolutePath, "-stay_open", "True", "-@", "-")

        val pb = ProcessBuilder(cmd)
        pb.directory(context.filesDir)
        pb.environment()["HOME"] = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        pb.environment()["PERL5LIB"] = PerlRuntime.perlLib.absolutePath

        return runCatching {
            val p = pb.start()
            val d = Daemon(
                p,
                BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8)),
                BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)),
                BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)),
            )
            // Probe before trusting it, under a deadline: a daemon that never
            // answers must not wedge the caller's thread forever.
            val probe = withDeadline(20_000) { d.exec(arrayOf("-ver")) }
                ?: Result("", "daemon probe timed out", false)
            if (!probe.ok || !Regex("""^\d+\.\d+""").containsMatchIn(probe.stdout.trim())) {
                startupError = buildString {
                    append("daemon probe returned '${probe.stdout.trim()}'")
                    if (probe.stderr.isNotBlank()) append("; stderr: ${probe.stderr}")
                    if (!d.alive) append("; process exited ${runCatching { p.exitValue() }.getOrNull()}")
                }
                d.close()
                null
            } else {
                d
            }
        }.onFailure {
            startupError = "${it::class.simpleName}: ${it.message}"
            Log.w(TAG, "daemon start failed, falling back to one-shot", it)
        }.getOrNull()
    }

    /** Runs [block] on a throwaway thread, giving up after [ms]. */
    private fun <T> withDeadline(ms: Long, block: () -> T): T? {
        var out: T? = null
        val t = Thread { out = runCatching(block).getOrNull() }
        t.isDaemon = true
        t.start()
        t.join(ms)
        if (t.isAlive) {
            t.interrupt()
            return null
        }
        return out
    }

    // ---------------------------------------------------------------- one-shot

    private fun oneShot(args: Array<out String>, timeoutMs: Long = ONE_SHOT_TIMEOUT_MS): Result {
        val raw = oneShotRaw(args, timeoutMs)
        return Result(
            String(raw.stdout, Charsets.UTF_8).trim(),
            String(raw.stderr, Charsets.UTF_8).trim(),
            raw.ok,
        )
    }

    /** stdout as bytes, for `-b` output that is not text at all. */
    class RawResult(val stdout: ByteArray, val stderr: ByteArray, val ok: Boolean)

    /**
     * Runs ExifTool once and returns its raw output.
     *
     * Both pipes are drained on their own threads. Reading stdout to EOF first
     * and stderr afterwards deadlocks the moment ExifTool writes more than a
     * pipe buffer of warnings, which a file with damaged maker notes does
     * easily: the child blocks writing to stderr, stdout never closes, and the
     * caller waits forever. The wait is also bounded, so a wedged interpreter
     * fails the operation instead of freezing the screen.
     */
    fun oneShotRaw(args: Array<out String>, timeoutMs: Long = ONE_SHOT_TIMEOUT_MS): RawResult {
        val cmd = mutableListOf(PerlRuntime.perlBinary.absolutePath)
        cmd += PerlRuntime.includeArgs()
        cmd += PerlRuntime.exifToolScript.absolutePath
        cmd += args
        return runCatching {
            val pb = ProcessBuilder(cmd)
            pb.directory(context.filesDir)
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            pb.environment()["PERL5LIB"] = PerlRuntime.perlLib.absolutePath
            val p = pb.start()

            val out = java.io.ByteArrayOutputStream()
            val err = java.io.ByteArrayOutputStream()
            val drainOut = drain(p.inputStream, out)
            val drainErr = drain(p.errorStream, err)

            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                drainOut.join(1_000)
                drainErr.join(1_000)
                return RawResult(
                    out.toByteArray(),
                    "timed out after $timeoutMs ms".toByteArray(),
                    false,
                )
            }
            drainOut.join(2_000)
            drainErr.join(2_000)
            RawResult(out.toByteArray(), err.toByteArray(), true)
        }.getOrElse {
            RawResult(ByteArray(0), (it.message ?: "exec failed").toByteArray(), false)
        }
    }

    private fun drain(from: java.io.InputStream, into: java.io.ByteArrayOutputStream): Thread {
        val t = Thread { runCatching { from.copyTo(into) } }
        t.isDaemon = true
        t.start()
        return t
    }

    // ------------------------------------------------------------------ public

    fun execute(vararg args: String): Result = synchronized(lock) {
        daemon?.let { d ->
            if (d.alive) {
                // Under a deadline. Only the start-up probe used to be bounded,
                // so a daemon that stopped answering mid-session hung whichever
                // screen asked it a question, with no way back.
                val r = withDeadline(COMMAND_TIMEOUT_MS) { runCatching { d.exec(args) }.getOrNull() }
                if (r != null && r.ok) return r
            }
            // Daemon died mid-session. Try once to bring it back, then give up
            // on it for the rest of the session rather than thrashing.
            runCatching { d.close() }
            daemon = null
            if (restarts < 1) {
                restarts++
                daemon = startDaemon()
                mode = if (daemon != null) Mode.DAEMON else Mode.ONE_SHOT
                daemon?.let { fresh ->
                    val r = runCatching { fresh.exec(args) }.getOrNull()
                    if (r != null && r.ok) return r
                }
            } else {
                mode = Mode.ONE_SHOT
            }
        }
        return oneShot(args)
    }

    fun readAllJson(path: String): Result =
        execute("-json", "-a", "-G:0:1:2", "-u", "-struct", "-charset", "utf8", path)

    fun readAll(path: String): Result =
        execute("-a", "-G1", "-s", "-charset", "utf8", path)

    fun transplantAll(source: String, target: String, overwriteOriginal: Boolean = true): Result {
        val args = mutableListOf("-tagsFromFile", source, "-all:all", "-unsafe", "-icc_profile", "-m")
        if (overwriteOriginal) args += "-overwrite_original"
        args += target
        return execute(*args.toTypedArray())
    }

    fun transplantTags(source: String, target: String, tags: List<String>, overwriteOriginal: Boolean = true): Result {
        val args = mutableListOf("-tagsFromFile", source)
        tags.forEach { args += "-$it" }
        args += "-m"
        if (overwriteOriginal) args += "-overwrite_original"
        args += target
        return execute(*args.toTypedArray())
    }

    fun version(): String = execute("-ver").stdout.trim()

    /**
     * Extracts a binary tag as bytes.
     *
     * Never routed through the daemon. The daemon speaks over a text reader,
     * so a thumbnail or a maker-note blob came back re-encoded and no longer
     * matched the bytes in the file, which made the hex view fiction. A
     * one-shot run hands the bytes over untouched.
     */
    fun binary(tag: String, path: String): ByteArray =
        oneShotRaw(arrayOf("-b", "-$tag", path)).stdout

    fun diagnose(): String = buildString {
        appendLine(PerlRuntime.describe())
        appendLine("mode      : $mode")
        appendLine("restarts  : $restarts")
        startupError?.let { appendLine("daemon err: $it") }
        appendLine("perl      : " + PerlRuntime.runOnce("-e", "print \"\$]\""))
        val oneShotVer = oneShot(arrayOf("-ver"))
        appendLine("one-shot  : '${oneShotVer.stdout}' ${oneShotVer.stderr}")
        appendLine("execute   : '${execute("-ver").stdout}'")
    }

    fun close() {
        synchronized(lock) { daemon?.close(); daemon = null }
    }

    companion object {
        private const val TAG = "ExifTool"

        /** A single metadata command. Generous, but never unbounded. */
        const val COMMAND_TIMEOUT_MS = 120_000L
        const val ONE_SHOT_TIMEOUT_MS = 180_000L

        @Volatile private var instance: ExifTool? = null

        /** Populated whenever [get] refuses to return an engine. */
        @Volatile var lastDiagnostics: String = "engine never started"
            private set

        /**
         * Why the last [get] returned null. Kept so callers can report the real
         * Perl error instead of a bare "engine did not start".
         */
        @Volatile
        var lastStartupDiagnostics: String = "engine has not been started yet"
            private set

        fun get(context: Context): ExifTool? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                if (!PerlRuntime.ensureReady(context)) {
                    lastStartupDiagnostics = "runtime assets not ready\n" + PerlRuntime.describe()
                    return null
                }
                val et = ExifTool(context.applicationContext)
                et.daemon = et.startDaemon()
                et.mode = if (et.daemon != null) Mode.DAEMON else Mode.ONE_SHOT
                Log.i(TAG, "engine ready in ${et.mode} mode")
                // Only hand back an engine that can actually answer.
                if (!Regex("""^\d+\.\d+""").containsMatchIn(et.version().trim())) {
                    lastStartupDiagnostics = et.diagnose()
                    Log.e(TAG, "engine unusable: $lastStartupDiagnostics")
                    return null
                }
                lastStartupDiagnostics = "engine started in ${et.mode} mode"
                instance = et
                return et
            }
        }
    }
}
