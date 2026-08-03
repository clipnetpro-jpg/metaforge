package com.metaforge.ai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Looks for invisible watermarks: marks a generator leaves in the pixels
 * themselves rather than in the metadata, which survive a screenshot and a
 * metadata wipe.
 *
 * The one this can actually read is the DWT quantisation watermark used by the
 * Stable Diffusion family through the `invisible-watermark` library. Its
 * algorithm is public, so the payload can be recovered and named rather than
 * guessed at: the chroma plane is Haar transformed, and in each 4x4 block of
 * the approximation band the largest coefficient is quantised to carry one bit.
 * Reading it back is the same walk, with a majority vote per bit position.
 *
 * Proprietary schemes such as Google SynthID and Digimarc cannot be read by
 * anyone outside the company that issued them; there is no public detector to
 * implement. Rather than pretend otherwise, this reports what it can prove and
 * says plainly what it cannot see.
 */
object WatermarkScanner {

    private const val SCALE = 36.0
    private const val BLOCK = 4
    private const val MIN_VOTES = 8

    /** Payloads that identify their generator outright. */
    private val KNOWN = listOf(
        Known("Stable Diffusion", asciiBits("StableDiffusionV1")),
        Known("Stable Diffusion XL", bitsOf("101100111110110010010000011110111011000110011110")),
    )

    private class Known(val name: String, val bits: IntArray)

    data class Reading(
        val length: Int,
        val bits: IntArray,
        val confidence: Float,
        val votesPerBit: Int,
    ) {
        /** The payload as text, when the bits spell something printable. */
        fun asText(): String? {
            if (length % 8 != 0) return null
            val bytes = ByteArray(length / 8)
            for (i in 0 until length / 8) {
                var v = 0
                for (b in 0 until 8) v = (v shl 1) or bits[i * 8 + b]
                bytes[i] = v.toByte()
            }
            val text = String(bytes, Charsets.ISO_8859_1)
            return if (text.all { it.code in 32..126 }) text else null
        }
    }

    data class Result(
        val evidence: List<Evidence>,
        val identified: String?,
        val payload: String?,
    )

    fun scan(bitmap: Bitmap): Result {
        val plane = chromaPlane(bitmap) ?: return Result(emptyList(), null, null)
        val ll = haarApproximation(plane)
        val evidence = mutableListOf<Evidence>()

        val readings = intArrayOf(136, 48, 128, 64, 32)
            .map { read(ll, it) }
            .filter { it.votesPerBit >= MIN_VOTES }

        // A payload that matches a published constant names its own maker.
        var identified: String? = null
        var payload: String? = null
        for (known in KNOWN) {
            val reading = readings.firstOrNull { it.length == known.bits.size } ?: continue
            val agree = known.bits.indices.count { known.bits[it] == reading.bits[it] }.toFloat() / known.bits.size
            if (agree >= 0.90f) {
                identified = known.name
                payload = reading.asText() ?: known.name
                evidence += Evidence(
                    id = "watermark-known",
                    kind = EvidenceKind.PROVENANCE,
                    title = "${known.name} watermark found in the pixels",
                    explanation = "The image carries the invisible mark that ${known.name} stamps " +
                        "into everything it renders. It sits in the picture itself, so it survives " +
                        "a metadata wipe and a screenshot.",
                    weight = 1f,
                    measurement = "%.0f%% of the published payload recovered from %d votes per bit"
                        .format(agree * 100, reading.votesPerBit),
                    decisive = true,
                )
                break
            }
        }

        if (identified == null) {
            val best = readings.maxByOrNull { it.confidence }
            if (best != null && best.confidence >= 0.45f) {
                val text = best.asText()
                payload = text
                evidence += Evidence(
                    id = "watermark-unknown",
                    kind = EvidenceKind.PROVENANCE,
                    title = if (text != null) "An invisible payload is embedded in the pixels"
                            else "A repeating invisible pattern is embedded in the pixels",
                    explanation = if (text != null) {
                        "A hidden ${best.length}-bit message decodes cleanly out of the chroma " +
                            "channel. Cameras do not do this; tools that want to mark their output do."
                    } else {
                        "The chroma channel repeats a quantised pattern with a fixed period, which " +
                            "is how watermarking tools carry a payload. The payload does not match " +
                            "any generator this app can name."
                    },
                    weight = 0.55f,
                    measurement = (text?.let { "\"$it\", " } ?: "") +
                        "period %d bits, agreement %.2f".format(best.length, best.confidence),
                )
            }
        }

        val lsb = lsbAnomaly(bitmap)
        if (lsb > 0.85f) {
            evidence += Evidence(
                id = "lsb",
                kind = EvidenceKind.FORENSIC,
                title = "The lowest bit of each pixel looks written, not natural",
                explanation = "In an untouched photograph the least significant bits are sensor " +
                    "noise. Here their distribution is flat in the way hidden data makes it, which " +
                    "is the classic signature of something stored inside the pixels.",
                weight = 0.3f,
                measurement = "least-significant-bit uniformity %.2f".format(lsb),
            )
        }

        return Result(evidence, identified, payload)
    }

    // ------------------------------------------------------------------ decode

    private fun read(ll: Plane, length: Int): Reading {
        val rows = ll.height / BLOCK
        val cols = ll.width / BLOCK
        val ones = IntArray(length)
        val total = IntArray(length)
        var index = 0

        for (by in 0 until rows) {
            for (bx in 0 until cols) {
                var pos = 1
                var best = -1.0
                for (k in 1 until BLOCK * BLOCK) {
                    val v = abs(ll[by * BLOCK + k / BLOCK, bx * BLOCK + k % BLOCK])
                    if (v > best) { best = v; pos = k }
                }
                val value = abs(ll[by * BLOCK + pos / BLOCK, bx * BLOCK + pos % BLOCK])
                val slot = index % length
                if (value % SCALE > SCALE / 2) ones[slot]++
                total[slot]++
                index++
            }
        }

        val bits = IntArray(length)
        var strength = 0f
        for (i in 0 until length) {
            val mean = if (total[i] == 0) 0.5 else ones[i].toDouble() / total[i]
            bits[i] = if (mean * 255 > 127) 1 else 0
            strength += (abs(mean - 0.5) * 2).toFloat()
        }
        return Reading(length, bits, strength / length, total.minOrNull() ?: 0)
    }

    // ------------------------------------------------------------------ planes

    private class Plane(val width: Int, val height: Int, val data: DoubleArray) {
        operator fun get(y: Int, x: Int): Double = data[y * width + x]
        operator fun set(y: Int, x: Int, v: Double) { data[y * width + x] = v }
    }

    /**
     * The U plane of BT.601 YUV, matching what the reference implementation
     * reads, cropped to a multiple of four so the block walk lines up.
     */
    private fun chromaPlane(bitmap: Bitmap): Plane? {
        val w = bitmap.width / 4 * 4
        val h = bitmap.height / 4 * 4
        if (w < 64 || h < 64) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val plane = Plane(w, h, DoubleArray(w * h))
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p).toDouble()
            val g = Color.green(p).toDouble()
            val b = Color.blue(p).toDouble()
            val y = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255)
            val u = (0.492 * (b - y) + 128.0).roundToInt().coerceIn(0, 255)
            plane.data[i] = u.toDouble()
        }
        return plane
    }

    /** One level of the Haar wavelet, approximation band only. */
    private fun haarApproximation(p: Plane): Plane {
        val w = p.width / 2
        val h = p.height / 2
        val out = Plane(w, h, DoubleArray(w * h))
        for (y in 0 until h) {
            for (x in 0 until w) {
                val a = p[2 * y, 2 * x]
                val b = p[2 * y, 2 * x + 1]
                val c = p[2 * y + 1, 2 * x]
                val d = p[2 * y + 1, 2 * x + 1]
                out[y, x] = (a + b + c + d) / 2.0
            }
        }
        return out
    }

    /**
     * Chi-square style test on the least significant bit plane. Hidden data
     * flattens the difference between each pair of adjacent intensity levels.
     */
    private fun lsbAnomaly(bitmap: Bitmap): Float {
        val w = minOf(bitmap.width, 512)
        val h = minOf(bitmap.height, 512)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        for (p in pixels) hist[Color.green(p)]++

        var matched = 0
        var pairs = 0
        for (i in 0 until 128) {
            val a = hist[2 * i]
            val b = hist[2 * i + 1]
            val sum = a + b
            if (sum < 40) continue
            pairs++
            val expected = sum / 2.0
            val deviation = abs(a - expected) / expected
            if (deviation < 0.06) matched++
        }
        return if (pairs < 8) 0f else matched.toFloat() / pairs
    }

    // ------------------------------------------------------------------ helpers

    private fun asciiBits(text: String): IntArray {
        val out = IntArray(text.length * 8)
        text.forEachIndexed { i, c ->
            for (b in 0 until 8) out[i * 8 + b] = (c.code shr (7 - b)) and 1
        }
        return out
    }

    private fun bitsOf(binary: String): IntArray =
        IntArray(binary.length) { if (binary[it] == '1') 1 else 0 }
}
