package com.metaforge.engine

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real ExifTool, running on device as a long lived `-stay_open` daemon.
 *
 * Cold starting Perl costs a second or two, so the process is started once and
 * every subsequent command is written to its stdin and terminated with
 * `-execute<n>`. ExifTool answers with `{ready<n>}` on stdout, which is the
 * delimiter we read up to. Typical round trip is a few tens of milliseconds.
 */
class ExifTool private constructor(
    private val process: Process,
    private val stdin: BufferedWriter,
    private val stdout: BufferedReader,
    private val stderr: BufferedReader,
) {
    data class Result(val stdout: String, val stderr: String, val ok: Boolean)

    private val seq = AtomicInteger(0)
    private val lock = Any()

    fun execute(vararg args: String): Result = synchronized(lock) {
        val n = seq.incrementAndGet()
        args.forEach { stdin.write(it); stdin.write("\n") }
        stdin.write("-echo4\n"); stdin.write("{stderr$n}\n")
        stdin.write("-execute$n\n")
        stdin.flush()

        val out = StringBuilder()
        val readyMarker = "{ready$n}"
        while (true) {
            val line = stdout.readLine() ?: return Result(out.toString(), "engine closed", false)
            if (line.trim() == readyMarker) break
            out.appendLine(line)
        }

        val err = StringBuilder()
        while (stderr.ready()) {
            val line = stderr.readLine() ?: break
            if (line.trim() == "{stderr$n}") break
            err.appendLine(line)
        }
        Result(out.toString().trim(), err.toString().trim(), true)
    }

    /** Full metadata of a file as JSON, every group, numeric duplicates kept. */
    fun readAllJson(path: String): Result =
        execute("-json", "-a", "-G:0:1:2", "-u", "-struct", "-charset", "utf8", path)

    /** Human readable value list. */
    fun readAll(path: String): Result =
        execute("-a", "-G1", "-s", "-charset", "utf8", path)

    /**
     * Copies every writable tag from [source] onto [target].
     * This is ExifTool's own -tagsFromFile, so fidelity matches the desktop tool.
     */
    fun transplantAll(source: String, target: String, overwriteOriginal: Boolean = true): Result {
        val args = mutableListOf("-tagsFromFile", source, "-all:all")
        if (overwriteOriginal) args += "-overwrite_original"
        args += target
        return execute(*args.toTypedArray())
    }

    /** Copies only the selected tags, e.g. listOf("EXIF:Make", "EXIF:Model", "GPS:all"). */
    fun transplantTags(source: String, target: String, tags: List<String>, overwriteOriginal: Boolean = true): Result {
        val args = mutableListOf("-tagsFromFile", source)
        tags.forEach { args += "-$it" }
        if (overwriteOriginal) args += "-overwrite_original"
        args += target
        return execute(*args.toTypedArray())
    }

    fun version(): String = execute("-ver").stdout.trim()

    /** Startup diagnostics: what the engine reports about itself. */
    fun diagnose(): String = buildString {
        appendLine(PerlRuntime.describe())
        appendLine("perl -V:version -> " + PerlRuntime.runOnce("-e", "print \"$]\""))
        val v = execute("-ver")
        appendLine("exiftool -ver   -> '${v.stdout.trim()}'")
        if (v.stderr.isNotBlank()) appendLine("stderr: ${v.stderr}")
        val alive = runCatching { process.exitValue(); "dead" }.getOrDefault("alive")
        appendLine("daemon: $alive")
    }

    fun close() {
        runCatching {
            stdin.write("-stay_open\nFalse\n")
            stdin.flush()
            process.waitFor()
        }
        runCatching { process.destroy() }
    }

    companion object {
        private const val TAG = "ExifTool"
        @Volatile private var instance: ExifTool? = null

        fun get(context: Context, stamp: String): ExifTool? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                if (!PerlRuntime.ensureReady(context, stamp)) return null
                return runCatching { start(context) }
                    .onFailure { Log.e(TAG, "daemon start failed", it) }
                    .getOrNull()
                    ?.also { instance = it }
            }
        }

        private fun start(context: Context): ExifTool {
            val cmd = mutableListOf(PerlRuntime.perlBinary.absolutePath)
            cmd += PerlRuntime.includeArgs()
            cmd += listOf(
                PerlRuntime.exifToolScript.absolutePath,
                "-stay_open", "True",
                "-@", "-",
                "-common_args", "-charset", "filename=utf8",
            )
            val pb = ProcessBuilder(cmd)
            pb.directory(context.filesDir)
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            pb.environment()["PERL5LIB"] = PerlRuntime.perlLib.absolutePath
            val p = pb.start()
            return ExifTool(
                p,
                BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8)),
                BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)),
                BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)),
            )
        }
    }
}
