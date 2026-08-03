package com.metaforge.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.sin
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the invisible watermark reader against the reference implementation.
 *
 * The fixture was produced by the `invisible-watermark` library itself, the one
 * Stable Diffusion ships with, carrying its standard payload. If this app's
 * reader agrees with it, the reader is right about real generated images rather
 * than merely self-consistent.
 */
@RunWith(AndroidJUnit4::class)
class WatermarkTest {

    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun readsTheStableDiffusionWatermarkFromAReferenceImage() {
        val bitmap = testAssets.open("sd_watermarked.jpg").use { BitmapFactory.decodeStream(it) }
        requireNotNull(bitmap) { "fixture did not decode" }

        val result = WatermarkScanner.scan(bitmap)
        assertEquals(
            "reference payload not recovered, evidence: " +
                result.evidence.joinToString { "${it.title} (${it.measurement})" },
            "Stable Diffusion",
            result.identified,
        )
        assertTrue("no decisive evidence recorded", result.evidence.any { it.decisive })
    }

    @Test
    fun findsNothingInAPlainImage() {
        val result = WatermarkScanner.scan(syntheticImage())
        assertNull(
            "claimed a watermark that is not there: " + result.evidence.joinToString { it.title },
            result.identified,
        )
    }

    @Test
    fun removesTheMarkAndProvesItIsGone() {
        val bitmap = testAssets.open("sd_watermarked.jpg").use { BitmapFactory.decodeStream(it) }
        requireNotNull(bitmap) { "fixture did not decode" }
        assertEquals("Stable Diffusion", WatermarkScanner.scan(bitmap).identified)

        val progress = kotlinx.coroutines.runBlocking {
            WatermarkRemover.clean(bitmap, WatermarkRemover.Options(removeHiddenMark = true))
                .toList()
        }
        assertTrue("no progress reported", progress.isNotEmpty())

        val report = requireNotNull(WatermarkRemover.lastReport) { "no report produced" }
        assertNull("the mark survived removal", WatermarkScanner.scan(report.cleaned).identified)
        assertTrue(
            "the picture was altered too much: ${report.visualDifference}",
            report.visualDifference < 4.0,
        )
    }

    @Test
    fun findsAMarkAfterTheImageHasBeenTrimmed() {
        val full = testAssets.open("sd_watermarked.jpg").use { BitmapFactory.decodeStream(it) }
        requireNotNull(full) { "fixture did not decode" }
        // Trim whole rows off the top and bottom: the reading grid starts in a
        // different place and the payload comes back rotated.
        val cropped = Bitmap.createBitmap(full, 0, 8, full.width, full.height - 40)
        assertEquals(
            "a trimmed copy hid the mark",
            "Stable Diffusion",
            WatermarkScanner.scan(cropped).identified,
        )
    }

    /** A smooth colour field: no watermark, no hidden payload, plenty of chroma. */
    private fun syntheticImage(): Bitmap {
        val w = 640
        val h = 640
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = (128 + 90 * sin(x / 61.0)).toInt().coerceIn(0, 255)
                val g = (120 + 80 * sin(y / 47.0 + 1.0)).toInt().coerceIn(0, 255)
                val b = (110 + 70 * sin((x + y) / 83.0)).toInt().coerceIn(0, 255)
                px[y * w + x] = Color.rgb(r, g, b)
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }
}
