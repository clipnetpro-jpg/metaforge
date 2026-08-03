package com.metaforge.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The forensic layer: measurements taken from the pixels themselves.
 *
 * Nothing here is a verdict. These are three cheap, well understood signals
 * that behave differently on synthesised and photographed images, each reported
 * with the number behind it so a user can disagree with the conclusion:
 *
 *  - **Error level analysis.** Re-compress the image and measure how much each
 *    block changes. A camera JPEG that has been saved once has an uneven ELA
 *    response; a freshly rendered image tends to respond uniformly.
 *  - **Sensor noise.** Real sensors leave high frequency noise whose strength
 *    varies with brightness. Diffusion output is unusually smooth, and smooth
 *    in the same way everywhere.
 *  - **Local detail spread.** Photographs have a focal plane, so sharpness
 *    varies across the frame. Fully synthetic frames are often uniformly sharp.
 *
 * Every one of these has honest failure modes (heavy denoising, screenshots,
 * re-encoding by a chat app), so the weights they contribute are deliberately
 * small: they can shade a verdict, never prove one.
 */
object ForensicAnalyzer {

    private const val BLOCK = 16
    private const val MAX_SIDE = 1024

    data class Analysis(
        val evidence: List<Evidence>,
        val heatmap: Bitmap?,
        val hotspots: List<Hotspot>,
    )

    fun analyse(source: Bitmap): Analysis {
        val bmp = downscale(source, MAX_SIDE)
        val w = bmp.width
        val h = bmp.height
        if (w < BLOCK * 2 || h < BLOCK * 2) {
            return Analysis(emptyList(), null, emptyList())
        }

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val luma = FloatArray(w * h) { i ->
            val p = pixels[i]
            0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
        }

        val ela = errorLevels(bmp, pixels, w, h)
        val noise = noiseResidual(luma, w, h)
        val detail = localDetail(luma, w, h)

        val cols = w / BLOCK
        val rows = h / BLOCK
        val evidence = mutableListOf<Evidence>()

        // --- ELA uniformity ---------------------------------------------------
        if (ela != null) {
            val stats = stats(ela.values)
            val cv = if (stats.mean > 0.01f) stats.sd / stats.mean else 0f
            evidence += Evidence(
                id = "ela",
                kind = EvidenceKind.FORENSIC,
                title = if (cv < 0.35f) "Compression response is unusually even"
                        else "Compression response varies naturally",
                explanation = "Re-compressing the image changes some areas more than others. " +
                    "A single-generation render reacts evenly across the frame; a photograph " +
                    "that has been through a camera pipeline does not.",
                weight = if (cv < 0.35f) 0.22f else -0.15f,
                measurement = "evenness %.2f across the frame".format(1f - cv.coerceIn(0f, 1f)),
            )
        }

        // --- sensor noise -----------------------------------------------------
        val noiseStats = stats(noise.values)
        evidence += Evidence(
            id = "noise",
            kind = EvidenceKind.FORENSIC,
            title = when {
                noiseStats.mean < 0.9f -> "Almost no sensor noise"
                noiseStats.mean < 2.2f -> "Low sensor noise"
                else -> "Sensor noise present"
            },
            explanation = "Every real sensor leaves a grain of high frequency noise, even in " +
                "bright light. Diffusion models render surfaces that are cleaner than any " +
                "sensor, though strong denoising or a screenshot can look the same.",
            weight = when {
                noiseStats.mean < 0.9f -> 0.28f
                noiseStats.mean < 2.2f -> 0.12f
                else -> -0.2f
            },
            measurement = "grain %.2f of one colour step".format(noiseStats.mean),
        )

        // --- detail distribution ----------------------------------------------
        val detailStats = stats(detail.values)
        val detailCv = if (detailStats.mean > 0.01f) detailStats.sd / detailStats.mean else 0f
        evidence += Evidence(
            id = "detail",
            kind = EvidenceKind.FORENSIC,
            title = if (detailCv < 0.5f) "Sharpness is uniform across the frame"
                    else "Sharpness falls off like a real lens",
            explanation = "A lens has one plane of focus, so detail drops away from it. A " +
                "rendered frame is often equally sharp everywhere. Flat scenes and wide-angle " +
                "phone shots can also look uniform, so this is a hint, not a finding.",
            weight = if (detailCv < 0.5f) 0.18f else -0.18f,
            measurement = "sharpness spread %.2f".format(detailCv),
        )

        // --- heatmap and hotspots ---------------------------------------------
        val combined = FloatArray(cols * rows) { i ->
            val e = ela?.values?.getOrNull(i)?.let { norm(it, ela.values) } ?: 0f
            val n = 1f - norm(noise.values[i], noise.values)
            val d = 1f - norm(detail.values[i], detail.values)
            (0.45f * e + 0.35f * n + 0.20f * d).coerceIn(0f, 1f)
        }
        val heatmap = heatmapOf(combined, cols, rows)
        val hotspots = hotspotsOf(combined, cols, rows, noise.values, detail.values)

        return Analysis(evidence, heatmap, hotspots)
    }

    // ------------------------------------------------------------------ blocks

    private class Grid(val values: FloatArray)

    private class Stats(val mean: Float, val sd: Float)

    private fun stats(v: FloatArray): Stats {
        if (v.isEmpty()) return Stats(0f, 0f)
        var sum = 0.0
        for (x in v) sum += x
        val mean = (sum / v.size).toFloat()
        var acc = 0.0
        for (x in v) acc += (x - mean).toDouble() * (x - mean)
        return Stats(mean, sqrt(acc / v.size).toFloat())
    }

    private fun norm(value: Float, all: FloatArray): Float {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (x in all) { lo = min(lo, x); hi = max(hi, x) }
        return if (hi - lo < 1e-4f) 0f else ((value - lo) / (hi - lo)).coerceIn(0f, 1f)
    }

    /** Mean absolute change per block after a JPEG round trip. */
    private fun errorLevels(bmp: Bitmap, pixels: IntArray, w: Int, h: Int): Grid? {
        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)) return null
        val bytes = out.toByteArray()
        val again = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (again.width != w || again.height != h) return null
        val other = IntArray(w * h)
        again.getPixels(other, 0, w, 0, 0, w, h)
        again.recycle()

        val cols = w / BLOCK
        val rows = h / BLOCK
        val g = FloatArray(cols * rows)
        for (by in 0 until rows) {
            for (bx in 0 until cols) {
                var acc = 0f
                for (y in by * BLOCK until (by + 1) * BLOCK) {
                    var i = y * w + bx * BLOCK
                    for (x in 0 until BLOCK) {
                        val a = pixels[i]
                        val b = other[i]
                        acc += (abs(Color.red(a) - Color.red(b)) +
                            abs(Color.green(a) - Color.green(b)) +
                            abs(Color.blue(a) - Color.blue(b))) / 3f
                        i++
                    }
                }
                g[by * cols + bx] = acc / (BLOCK * BLOCK)
            }
        }
        return Grid(g)
    }

    /** High frequency residual per block: |pixel - 3x3 mean|. */
    private fun noiseResidual(luma: FloatArray, w: Int, h: Int): Grid {
        val cols = w / BLOCK
        val rows = h / BLOCK
        val g = FloatArray(cols * rows)
        for (by in 0 until rows) {
            for (bx in 0 until cols) {
                var acc = 0f
                var n = 0
                for (y in max(1, by * BLOCK) until min(h - 1, (by + 1) * BLOCK)) {
                    for (x in max(1, bx * BLOCK) until min(w - 1, (bx + 1) * BLOCK)) {
                        var sum = 0f
                        for (dy in -1..1) for (dx in -1..1) sum += luma[(y + dy) * w + x + dx]
                        acc += abs(luma[y * w + x] - sum / 9f)
                        n++
                    }
                }
                g[by * cols + bx] = if (n == 0) 0f else acc / n
            }
        }
        return Grid(g)
    }

    /** Laplacian energy per block: how much real detail lives there. */
    private fun localDetail(luma: FloatArray, w: Int, h: Int): Grid {
        val cols = w / BLOCK
        val rows = h / BLOCK
        val g = FloatArray(cols * rows)
        for (by in 0 until rows) {
            for (bx in 0 until cols) {
                var acc = 0f
                var n = 0
                for (y in max(1, by * BLOCK) until min(h - 1, (by + 1) * BLOCK)) {
                    for (x in max(1, bx * BLOCK) until min(w - 1, (bx + 1) * BLOCK)) {
                        val c = luma[y * w + x]
                        val lap = 4f * c - luma[(y - 1) * w + x] - luma[(y + 1) * w + x] -
                            luma[y * w + x - 1] - luma[y * w + x + 1]
                        acc += abs(lap)
                        n++
                    }
                }
                g[by * cols + bx] = if (n == 0) 0f else acc / n
            }
        }
        return Grid(g)
    }

    private fun heatmapOf(v: FloatArray, cols: Int, rows: Int): Bitmap {
        val bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        val px = IntArray(cols * rows)
        for (i in v.indices) {
            val s = v[i].coerceIn(0f, 1f)
            // transparent where nothing is odd, amber to red where it is
            val alpha = (s * 210).toInt().coerceIn(0, 210)
            val r = 255
            val g = (200 * (1f - s)).toInt()
            px[i] = Color.argb(alpha, r, g, 40)
        }
        bmp.setPixels(px, 0, cols, 0, 0, cols, rows)
        return bmp
    }

    private fun hotspotsOf(
        v: FloatArray,
        cols: Int,
        rows: Int,
        noise: FloatArray,
        detail: FloatArray,
    ): List<Hotspot> {
        data class Candidate(val idx: Int, val score: Float)
        val ranked = v.indices.map { Candidate(it, v[it]) }
            .sortedByDescending { it.score }
            .filter { it.score > 0.62f }

        val taken = mutableListOf<Hotspot>()
        for (c in ranked) {
            if (taken.size >= 4) break
            val bx = c.idx % cols
            val by = c.idx / cols
            val bounds = RectF(
                (bx - 1).coerceAtLeast(0).toFloat() / cols,
                (by - 1).coerceAtLeast(0).toFloat() / rows,
                (bx + 2).coerceAtMost(cols).toFloat() / cols,
                (by + 2).coerceAtMost(rows).toFloat() / rows,
            )
            if (taken.any { overlaps(it.bounds, bounds) }) continue
            val reason = when {
                noise[c.idx] < 0.8f -> "no sensor grain"
                detail[c.idx] < 1.2f -> "detail rendered flat"
                else -> "compression response even"
            }
            taken += Hotspot(
                bounds = bounds,
                score = c.score,
                reason = reason,
                detail = null,
            )
        }
        return taken
    }

    private fun overlaps(a: RectF, b: RectF): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt(), (src.height * scale).toInt(), true,
        )
    }
}
