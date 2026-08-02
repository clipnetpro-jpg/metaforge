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
    fun ensureReady(context: Context, stamp: String): Boolean {
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
            unzipAsset(context, "perl5.zip", root)
            unzipAsset(context, "exiftool.zip", root)
            prefs.edit().putString(KEY_STAMP, stamp).apply()
        }

        ready = exifToolScript.exists()
        return ready
    }

    /** Arguments that put Perl's bundled library tree on @INC. */
    fun includeArgs(): List<String> = listOf("-I", perlLib.absolutePath)

    /** Runs perl once and returns stdout+stderr. Used for diagnostics only. */
    fun runOnce(vararg args: String, timeoutMs: Long = 20_000): String {
        val cmd = mutableListOf(perlBinary.absolutePath)
        cmd += includeArgs()
        cmd += args
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
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
