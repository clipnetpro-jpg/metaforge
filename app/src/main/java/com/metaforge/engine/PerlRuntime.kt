package com.metaforge.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Locates and prepares the bundled Perl interpreter.
 *
 * The interpreter ships as `libperl.so` inside jniLibs. Android extracts it into
 * `nativeLibraryDir` at install time, which is the only app-owned directory that
 * stays executable on Android 10+ (W^X). Perl is built fully static
 * (--all-static, -Dusedl=undef) so there are no XS shared objects to relocate.
 *
 * The Perl standard library and the ExifTool distribution ship as zipped assets
 * and are unpacked to filesDir on first launch (or after an app update).
 */
object PerlRuntime {

    private const val TAG = "PerlRuntime"
    private const val PREFS = "metaforge_runtime"
    private const val KEY_STAMP = "assets_stamp"

    lateinit var perlBinary: File
        private set
    lateinit var perlLib: File
        private set
    lateinit var exifToolScript: File
        private set

    @Volatile private var ready = false

    @Synchronized
    /**
     * The runtime version is derived from the app itself, never passed in.
     * An earlier build let callers supply their own stamp, and two callers
     * disagreeing caused the runtime directory to be deleted underneath a
     * running ExifTool process.
     */
    private fun stampOf(context: Context): String =
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName}-${pi.lastUpdateTime}"
        }.getOrDefault("unknown")

    fun ensureReady(context: Context): Boolean {
        val stamp = stampOf(context)
        if (ready) return true

        perlBinary = File(context.applicationInfo.nativeLibraryDir, "libperl.so")
        if (!perlBinary.exists()) {
            Log.e(TAG, "libperl.so missing from ${perlBinary.parent}")
            return false
        }
        if (!perlBinary.canExecute()) perlBinary.setExecutable(true, false)

        val root = File(context.filesDir, "runtime")
        perlLib = File(root, "perl5")
        exifToolScript = File(root, "exiftool/exiftool")

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_STAMP, null) != stamp || !exifToolScript.exists()) {
            root.deleteRecursively()
            root.mkdirs()
            // Shared pure-Perl tree...
            unzipAsset(context, "perl5.zip", root)
            // ...plus the ABI-specific part (Config.pm, Config_heavy.pl, arch dir).
            // Without this Perl cannot load Config and almost every module fails.
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { hasAsset(context, "perl5-$it.zip") }
            if (abi == null) {
                Log.e(TAG, "no perl5-<abi>.zip for ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                return false
            }
            Log.i(TAG, "installing perl runtime for $abi")
            unzipAsset(context, "perl5-$abi.zip", root)
            unzipAsset(context, "exiftool.zip", root)
            prefs.edit().putString(KEY_STAMP, stamp).apply()
        }

        ready = exifToolScript.exists()
        return ready
    }

    /** Arguments that put Perl's bundled library tree and ExifTool on @INC. */
    fun includeArgs(): List<String> = listOf(
        "-I", perlLib.absolutePath,
        "-I", File(exifToolScript.parentFile, "lib").absolutePath,
    )

    private fun hasAsset(context: Context, name: String): Boolean =
        runCatching { context.assets.open(name).close(); true }.getOrDefault(false)

    /** Everything the engine knows about itself, for the diagnostics screen. */
    fun describe(): String = buildString {
        appendLine("binary   : ${if (::perlBinary.isInitialized) perlBinary.absolutePath else "?"}")
        appendLine("lib      : ${if (::perlLib.isInitialized) perlLib.absolutePath else "?"}")
        appendLine("exiftool : ${if (::exifToolScript.isInitialized) exifToolScript.absolutePath else "?"}")
        if (::perlLib.isInitialized) {
            appendLine("Config.pm: ${File(perlLib, "Config.pm").exists()}")
            appendLine("libs     : ${perlLib.list()?.size ?: 0} entries")
        }
    }

    /** Runs perl once and returns stdout+stderr. Used for diagnostics only. */
    fun runOnce(vararg args: String, timeoutMs: Long = 30_000): String {
        val cmd = mutableListOf(perlBinary.absolutePath)
        cmd += includeArgs()
        cmd += args
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        // The timeout was declared but never enforced, so a wedged interpreter
        // could hang the caller, and did: it stalled CI for twenty minutes.
        val out = StringBuilder()
        val reader = Thread {
            runCatching { out.append(p.inputStream.bufferedReader().readText()) }
        }
        reader.isDaemon = true
        reader.start()
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            reader.join(1_000)
            return (out.toString() + "\n[timed out after ${timeoutMs} ms]").trim()
        }
        reader.join(2_000)
        return out.toString().trim()
    }

    private fun unzipAsset(context: Context, assetName: String, target: File) {
        context.assets.open(assetName).use { raw ->
            ZipInputStream(raw.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val outFile = File(target, entry.name)
                    if (!outFile.canonicalPath.startsWith(target.canonicalPath)) {
                        throw SecurityException("zip traversal blocked: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                }
            }
        }
    }
}
