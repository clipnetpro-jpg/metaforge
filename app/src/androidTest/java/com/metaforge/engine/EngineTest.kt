package com.metaforge.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.flow.toList

/**
 * Runs on a real Android device/emulator in CI. These are the checks that prove
 * the bundled Perl and ExifTool actually execute under Android, not just that
 * the project compiles.
 */
@RunWith(AndroidJUnit4::class)
class EngineTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** The engine, or a failure that carries the real Perl error with it. */
    private fun engine(): ExifTool =
        ExifTool.get(ctx)
            ?: throw AssertionError("engine unavailable\n" + ExifTool.lastStartupDiagnostics)

    @Test
    fun perlInterpreterRuns() {
        assertTrue("runtime not ready", PerlRuntime.ensureReady(ctx))
        val out = PerlRuntime.runOnce("-e", "print 42 + 1")
        assertTrue("perl said: $out", out.contains("43"))
    }

    @Test
    fun perlCoreModulesLoad() {
        assertTrue("runtime not ready", PerlRuntime.ensureReady(ctx))
        val out = PerlRuntime.runOnce(
            "-MPOSIX", "-MFcntl", "-MIO::File", "-MEncode", "-MList::Util",
            "-e", "print 'MODULES OK'",
        )
        assertTrue(
            "core XS modules did not load.\n--- output ---\n$out\n--- runtime ---\n" +
                PerlRuntime.describe(),
            out.contains("MODULES OK"),
        )
    }

    @Test
    fun exifToolScriptRunsOneShot() {
        assertTrue(PerlRuntime.ensureReady(ctx))
        val out = PerlRuntime.runOnce(PerlRuntime.exifToolScript.absolutePath, "-ver").trim()
        android.util.Log.i("ExifTool", "one-shot -ver output:\n$out")
        android.util.Log.i("PerlRuntime", PerlRuntime.describe())
        // Anchored: an "@INC" error also contains a x.y number, and matching that
        // is how this test used to pass while the engine was completely broken.
        assertTrue(
            "exiftool one-shot failed.\n--- output ---\n$out\n--- runtime ---\n" +
                PerlRuntime.describe(),
            Regex("""^\d+\.\d+""").containsMatchIn(out),
        )
    }

    @Test
    fun exifToolReportsVersion() {
        val et = engine()
        val v = et.version()
        android.util.Log.i("ExifTool", "mode=${et.mode} version=$v")
        assertTrue("unexpected version: '$v'\n" + et.diagnose(),
                   Regex("""^\d+\.\d+""").containsMatchIn(v.trim()))
    }

    @Test
    fun readsAndWritesRealMetadata() {
        val et = engine()
        val jpg = File(ctx.cacheDir, "probe.jpg")
        jpg.writeBytes(MINIMAL_JPEG)

        val write = et.execute("-EXIF:Artist=MetaForge", "-overwrite_original", jpg.absolutePath)
        assertTrue("write failed: ${write.stdout} ${write.stderr}", write.ok)

        val read = et.readAllJson(jpg.absolutePath)
        assertTrue("artist not round-tripped: ${read.stdout}", read.stdout.contains("MetaForge"))
    }

    @Test
    fun transplantsMetadataBetweenFiles() {
        val et = engine()
        val src = File(ctx.cacheDir, "src.jpg").apply { writeBytes(MINIMAL_JPEG) }
        val dst = File(ctx.cacheDir, "dst.jpg").apply { writeBytes(MINIMAL_JPEG) }

        et.execute("-EXIF:Make=Canon", "-EXIF:Model=EOS R6", "-overwrite_original", src.absolutePath)
        val res = et.transplantAll(src.absolutePath, dst.absolutePath)
        assertTrue("transplant failed: ${res.stderr}", res.ok)

        val read = et.readAllJson(dst.absolutePath).stdout
        assertTrue("Make missing after transplant: $read", read.contains("Canon"))
        assertTrue("Model missing after transplant: $read", read.contains("EOS R6"))
    }

    @Test
    fun transplantReportsFullCoverage() {
        val et = engine()
        val engine = TransplantEngine(et)
        val src = File(ctx.cacheDir, "cov_src.jpg").apply { writeBytes(MINIMAL_JPEG) }
        val dst = File(ctx.cacheDir, "cov_dst.jpg").apply { writeBytes(MINIMAL_JPEG) }

        et.execute(
            "-EXIF:Make=Sony", "-EXIF:Model=ILCE-7M4", "-EXIF:Artist=Dev BD",
            "-EXIF:DateTimeOriginal=2024:05:01 12:00:00",
            "-GPS:GPSLatitude=23.8103", "-GPS:GPSLatitudeRef=N",
            "-GPS:GPSLongitude=90.4125", "-GPS:GPSLongitudeRef=E",
            "-XMP:Creator=MetaForge", "-IPTC:By-line=MetaForge",
            "-overwrite_original", src.absolutePath,
        )

        val progress = kotlinx.coroutines.runBlocking {
            engine.transplant(src, dst).toList()
        }
        assertTrue("no progress emitted", progress.isNotEmpty())
        assertTrue("operation did not finish", progress.last().finished)

        val report = requireNotNull(engine.lastReport) { "no report produced" }
        assertTrue(
            "missing tags after transplant: " + report.missing.joinToString { it.tag },
            report.complete,
        )
        assertTrue("nothing was copied", report.copiedCount > 5)
    }

    @Test
    fun privacyStripperLeavesNothingIdentifying() {
        val et = engine()
        val stripper = PrivacyStripper(et)
        val f = File(ctx.cacheDir, "priv.jpg").apply { writeBytes(MINIMAL_JPEG) }

        et.execute(
            "-EXIF:Make=Google", "-EXIF:Model=Pixel 9 Pro",
            "-EXIF:Artist=Dev BD", "-EXIF:Software=MetaForge",
            "-EXIF:DateTimeOriginal=2024:05:01 12:00:00",
            "-GPS:GPSLatitude=23.8103", "-GPS:GPSLatitudeRef=N",
            "-GPS:GPSLongitude=90.4125", "-GPS:GPSLongitudeRef=E",
            "-XMP:Creator=Dev BD", "-IPTC:By-line=Dev BD",
            "-overwrite_original", f.absolutePath,
        )
        // Read numerically: without -n ExifTool formats GPS as 23 deg 48' 37",
        // so asserting on the decimal it was written with silently fails.
        val loaded = et.execute(
            "-json", "-a", "-u", "-G1", "-n", "-charset", "utf8", f.absolutePath,
        ).stdout
        assertTrue("setup failed, no GPS written: $loaded", loaded.contains("23.81"))

        kotlinx.coroutines.runBlocking { stripper.strip(f).toList() }

        val report = requireNotNull(stripper.lastReport)
        assertTrue("left behind: " + report.remaining.joinToString(), report.clean)

        val after = et.execute(
            "-json", "-a", "-u", "-G1", "-n", "-charset", "utf8", f.absolutePath,
        ).stdout
        listOf("Pixel 9 Pro", "23.81", "90.41", "Dev BD", "Google").forEach {
            assertTrue("still leaks \"$it\": $after", !after.contains(it))
        }
    }

    companion object {
        /** 1x1 white JPEG, smallest thing ExifTool will accept as a real image. */
        private val MINIMAL_JPEG: ByteArray = android.util.Base64.decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a" +
            "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA" +
            "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
            android.util.Base64.DEFAULT,
        )
    }
}
