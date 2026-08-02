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
            // Probe before trusting it.
            val probe = d.exec(arrayOf("-ver"))
            if (!probe.ok || !Regex("""\d+\.\d+""").containsMatchIn(probe.stdout)) {
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

    // ---------------------------------------------------------------- one-shot

    private fun oneShot(args: Array<out String>): Result {
        val cmd = mutableListOf(PerlRuntime.perlBinary.absolutePath)
        cmd += PerlRuntime.includeArgs()
        cmd += PerlRuntime.exifToolScript.absolutePath
        cmd += args
        return runCatching {
            val pb = ProcessBuilder(cmd)
            pb.directory(context.filesDir)
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            Result(out.trim(), err.trim(), true)
        }.getOrElse { Result("", it.message ?: "exec failed", false) }
    }

    // ------------------------------------------------------------------ public

    fun execute(vararg args: String): Result = synchronized(lock) {
        daemon?.let { d ->
            if (d.alive) {
                val r = runCatching { d.exec(args) }.getOrNull()
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

        @Volatile private var instance: ExifTool? = null

        fun get(context: Context, stamp: String): ExifTool? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                if (!PerlRuntime.ensureReady(context, stamp)) return null
                val et = ExifTool(context.applicationContext)
                et.daemon = et.startDaemon()
                et.mode = if (et.daemon != null) Mode.DAEMON else Mode.ONE_SHOT
                Log.i(TAG, "engine ready in ${et.mode} mode")
                // Only hand back an engine that can actually answer.
                if (!Regex("""\d+\.\d+""").containsMatchIn(et.version())) {
                    Log.e(TAG, "engine unusable: ${et.diagnose()}")
                    return null
                }
                instance = et
                return et
            }
        }
    }
}
