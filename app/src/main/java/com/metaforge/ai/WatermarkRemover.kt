package com.metaforge.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.metaforge.core.OperationProgress
import com.metaforge.core.Stage
import com.metaforge.core.progressFlow
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow

/**
 * Takes a hidden mark back out of a picture, and proves it is gone.
 *
 * The mark is carried by nudging one colour coefficient in every small block
 * into a chosen half of a step. Overwriting that choice with a random position
 * inside the same step destroys the message while leaving the value inside the
 * range it already occupied, so the picture is untouched to the eye and stays
 * the same size and format.
 *
 * Removal is never automatic. Nothing here runs unless the user asks for it,
 * and the result is checked afterwards rather than assumed.
 */
object WatermarkRemover {

    private const val QUANT = 36.0
    private const val BLOCK = 4

    data class Options(
        val removeHiddenMark: Boolean = true,
        val scrubFinestDetail: Boolean = false,
    )

    data class Report(
        val cleaned: Bitmap,
        val markBefore: String?,
        val markAfter: String?,
        val stillPresent: Boolean,
        val visualDifference: Double,
    )

    private val stages = listOf(
        Stage("read", "Reading what is hidden"),
        Stage("erase", "Erasing it from the colours"),
        Stage("detail", "Clearing the finest detail"),
        Stage("verify", "Checking it is really gone"),
    )

    @Volatile
    var lastReport: Report? = null
        private set

    fun clean(source: Bitmap, options: Options = Options()): Flow<OperationProgress> = progressFlow(
        title = "Removing the hidden mark",
        stages = stages,
    ) {
        val before = stage("read") { WatermarkScanner.scan(source) }
        update("read", 1f, before.identified ?: before.payload ?: "checking the whole picture")

        var working = source.copy(Bitmap.Config.ARGB_8888, true)

        if (options.removeHiddenMark && before.removable) {
            stage("erase") { erase(working) }
        } else if (options.removeHiddenMark) {
            // Running the eraser over a picture with no verified mark would
            // change pixels for nothing and then announce success at removing
            // something that was never there.
            skip("erase", "no verified mark in this picture, nothing to erase")
        } else {
            skip("erase", "left in place")
        }

        if (options.scrubFinestDetail) {
            stage("detail") { scrubLowBits(working) }
        } else {
            skip("detail", "left in place")
        }

        val after = stage("verify") { WatermarkScanner.scan(working) }
        val diff = difference(source, working)
        lastReport = Report(
            cleaned = working,
            markBefore = before.identified ?: before.payload
                ?: if (before.removable) "an unnamed repeating pattern" else null,
            markAfter = after.identified ?: after.payload,
            stillPresent = after.identified != null,
            visualDifference = diff,
        )
        update(
            "verify", 1f,
            if (after.identified == null) "nothing readable left" else "the mark is still there",
        )
    }

    /** Overwrites the carrier position in every block with a random one. */
    private fun erase(bitmap: Bitmap) {
        val random = Random(System.nanoTime())
        val image = ChromaPlanes.read(bitmap) ?: return

        for (channel in 0..1) {
            val plane = if (channel == 0) image.u else image.v
            val wavelet = ChromaPlanes.forward(plane)
            val ll = wavelet.ll
            val rows = ll.height / BLOCK
            val cols = ll.width / BLOCK
            for (by in 0 until rows) {
                for (bx in 0 until cols) {
                    var pos = 1
                    var peak = -1.0
                    for (k in 1 until BLOCK * BLOCK) {
                        val v = abs(ll[by * BLOCK + k / BLOCK, bx * BLOCK + k % BLOCK])
                        if (v > peak) { peak = v; pos = k }
                    }
                    val y = by * BLOCK + pos / BLOCK
                    val x = bx * BLOCK + pos % BLOCK
                    val value = ll[y, x]
                    val sign = if (value >= 0) 1.0 else -1.0
                    val magnitude = abs(value)
                    val step = Math.floor(magnitude / QUANT) * QUANT
                    ll[y, x] = sign * (step + random.nextDouble() * QUANT)
                }
            }
            ChromaPlanes.inverse(wavelet, plane)
        }
        ChromaPlanes.write(image, bitmap)
    }

    /** Replaces the last bit of every channel with noise. */
    private fun scrubLowBits(bitmap: Bitmap) {
        val random = Random(System.nanoTime())
        val w = bitmap.width
        val row = IntArray(w)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                row[x] = Color.argb(
                    Color.alpha(p),
                    (Color.red(p) and 0xFE) or random.nextInt(2),
                    (Color.green(p) and 0xFE) or random.nextInt(2),
                    (Color.blue(p) and 0xFE) or random.nextInt(2),
                )
            }
            bitmap.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /** Average change per colour step, so the user can see it is imperceptible. */
    private fun difference(a: Bitmap, b: Bitmap): Double {
        val w = minOf(a.width, b.width, 256)
        val h = minOf(a.height, b.height, 256)
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h)
        b.getPixels(pb, 0, w, 0, 0, w, h)
        var sum = 0.0
        for (i in pa.indices) {
            sum += abs(Color.red(pa[i]) - Color.red(pb[i])).toDouble()
            sum += abs(Color.green(pa[i]) - Color.green(pb[i])).toDouble()
            sum += abs(Color.blue(pa[i]) - Color.blue(pb[i])).toDouble()
        }
        return sum / (pa.size * 3)
    }
}
