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
    private val stamp = "test"

    @Test
    fun perlInterpreterRuns() {
        assertTrue("runtime not ready", PerlRuntime.ensureReady(ctx, stamp))
        val out = PerlRuntime.runOnce("-e", "print 42 + 1")
        assertTrue("perl said: $out", out.contains("43"))
    }

    @Test
    fun exifToolReportsVersion() {
        val et = requireNotNull(ExifTool.get(ctx, stamp)) { "ExifTool daemon did not start" }
        val v = et.version()
        assertTrue("unexpected version: $v", Regex("""^\d+\.\d+""").containsMatchIn(v))
    }

    @Test
    fun readsAndWritesRealMetadata() {
        val et = requireNotNull(ExifTool.get(ctx, stamp))
        val jpg = File(ctx.cacheDir, "probe.jpg")
        jpg.writeBytes(MINIMAL_JPEG)

        val write = et.execute("-EXIF:Artist=MetaForge", "-overwrite_original", jpg.absolutePath)
        assertTrue("write failed: ${write.stdout} ${write.stderr}", write.ok)

        val read = et.readAllJson(jpg.absolutePath)
        assertTrue("artist not round-tripped: ${read.stdout}", read.stdout.contains("MetaForge"))
    }

    @Test
    fun transplantsMetadataBetweenFiles() {
        val et = requireNotNull(ExifTool.get(ctx, stamp))
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
        val et = requireNotNull(ExifTool.get(ctx, stamp))
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
