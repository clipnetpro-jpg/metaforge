package com.metaforge.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds marks hidden inside the picture itself rather than in its metadata.
 *
 * A hidden mark can sit anywhere, so the whole frame is searched: the image is
 * covered tile by tile, both colour channels are read, and every alignment of
 * the reading grid is tried, which is what lets a mark still be found after the
 * picture has been cropped. Whatever is found is reported with the exact part
 * of the image it was found in, so it can be shown on the photo.
 *
 * Some marks cannot be read by anyone but the company that issued them. Those
 * are reported as unreadable rather than pretended away.
 */
object WatermarkScanner {

    private const val QUANT = 36.0
    private const val BLOCK = 4
    private const val MIN_VOTES = 12
    private const val STRUCTURE_Z = 4.5
    private const val MAX_TILE = 2048
    private const val KNOWN_AGREEMENT = 0.90f

    private val LENGTHS = intArrayOf(136, 48, 128, 64, 32)

    private class Known(val name: String, val bits: IntArray)

    private val KNOWN_MARKS = listOf(
        Known("Stable Diffusion", asciiBits("StableDiffusionV1")),
        Known("Stable Diffusion XL", bitsOf("101100111110110010010000011110111011000110011110")),
    )

    /** One decoded reading of a region at a given period. */
    data class Reading(
        val length: Int,
        val bits: IntArray,
        val strength: Double,
        val votesPerBit: Int,
        val region: RectF,
        val channel: Int,
        val blockBits: ByteArray = ByteArray(0),
        val gridCols: Int = 0,
        val gridRows: Int = 0,
    ) {
        fun rotated(by: Int): Reading =
            copy(bits = IntArray(length) { bits[(it + by) % length] })

        fun asText(): String? {
            if (length % 8 != 0) return null
            val bytes = ByteArray(length / 8)
            for (i in bytes.indices) {
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
        val marks: List<Hotspot>,
        val removable: Boolean,
        /** Where the mark reads, block by block, at the same shape as the picture. */
        val coverage: Bitmap? = null,
        val coveragePercent: Int = 0,
    )

    fun scan(bitmap: Bitmap): Result = build(search(bitmap), lsbUniformity(bitmap))

    /**
     * For pictures too large to hold in memory. The file is read at full size in
     * pieces, because shrinking it would wipe out the mark being looked for.
     */
    fun scanFile(path: String): Result = build(scanFileTiles(path), lsbFromFile(path))

    private fun build(searchResult: Reading?, lsb: LsbReport): Result {
        val evidence = mutableListOf<Evidence>()
        val marks = mutableListOf<Hotspot>()
        var coverage: Bitmap? = null
        var coveragePercent = 0

        val best = searchResult
        var identified: String? = null
        var payload: String? = null

        if (best != null) {
            for (known in KNOWN_MARKS) {
                if (known.bits.size != best.length) continue
                // Trimming a picture moves where each bit lands, so the payload
                // can come back rotated. Every starting point is tried.
                var agree = 0f
                var rotation = 0
                for (r in known.bits.indices) {
                    val hits = known.bits.indices.count {
                        known.bits[it] == best.bits[(it + r) % best.length]
                    }.toFloat() / known.bits.size
                    if (hits > agree) { agree = hits; rotation = r }
                }
                if (agree >= KNOWN_AGREEMENT) {
                    identified = known.name
                    payload = best.rotated(rotation).asText()
                    val map = coverageOf(best, known.bits, rotation)
                    coverage = map.first
                    coveragePercent = map.second
                    evidence += Evidence(
                        id = "watermark-known",
                        kind = EvidenceKind.PROVENANCE,
                        title = "$identified leaves a hidden mark, and it is here",
                        explanation = "The mark is written into the colours of the picture " +
                            "itself, so wiping the metadata does not touch it and it comes " +
                            "through sharing and re-saving. A camera has no reason to put one " +
                            "there.",
                        weight = 1f,
                        measurement = "${(agree * 100).toInt()}% of the mark read back cleanly",
                        decisive = true,
                    )
                    marks += Hotspot(best.region, 1f, identified, "hidden mark")
                    break
                }
            }

            if (identified == null && best.strength >= STRUCTURE_Z && halvesAgree(best)) {
                val text = best.asText()
                payload = text
                evidence += Evidence(
                    id = "watermark-unknown",
                    kind = EvidenceKind.PROVENANCE,
                    title = if (text != null) "A hidden message is written into this picture"
                            else "A hidden repeating mark is written into this picture",
                    explanation = if (text != null) {
                        "A readable message comes out of the colour channel. Cameras do not write " +
                            "one; tools that want their output traceable do."
                    } else {
                        "A deliberate repeating pattern sits in the colour channel. It carries " +
                            "something, though not in a form this app can name."
                    },
                    weight = 0.55f,
                    measurement = text?.let { "\"$it\"" } ?: "repeats every ${best.length} steps",
                )
                marks += Hotspot(best.region, 0.8f, "hidden mark", null)
            }
        }

        if (lsb.score > 0.92f) {
            // Informational only. Editing, resizing and chat-app compression
            // all flatten fine-detail statistics; a JPEG that was ever
            // converted to PNG trips this constantly. It said "hidden data"
            // over an ordinary photograph, so it no longer moves the verdict
            // and no longer draws a box.
            evidence += Evidence(
                id = "lsb",
                kind = EvidenceKind.FORENSIC,
                title = "Fine-detail statistics are unusual",
                explanation = "The finest detail of the pixels is more even than raw camera " +
                    "output. This is common after editing, resizing or messaging-app " +
                    "compression, so on its own it says nothing about AI.",
                weight = 0f,
                measurement = "${(lsb.score * 100).toInt()}% of sampled regions",
            )
        }

        if (identified != null) {
            evidence += Evidence(
                id = "watermark-spread",
                kind = EvidenceKind.PROVENANCE,
                title = "The mark covers the whole picture, not one corner",
                explanation = "It is repeated in every small block of the frame, which is why " +
                    "trimming or resaving does not shake it off. The overlay shows how strongly " +
                    "each part of the picture carries it.",
                weight = 0f,
                measurement = "$coveragePercent% of the picture reads the mark",
            )
        }

        return Result(
            evidence = evidence,
            identified = identified,
            payload = payload,
            marks = marks,
            removable = identified != null || evidence.any { it.id == "watermark-unknown" },
            coverage = coverage,
            coveragePercent = coveragePercent,
        )
    }

    /**
     * A picture of where the mark sits: one pixel per block, bright where that
     * block's bit agrees with the recovered payload.
     */
    private fun coverageOf(reading: Reading, known: IntArray, rotation: Int): Pair<Bitmap?, Int> {
        if (reading.gridCols <= 0 || reading.gridRows <= 0) return null to 0
        val cols = reading.gridCols
        val rows = reading.gridRows
        val map = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        val px = IntArray(cols * rows)
        var agreed = 0
        for (i in 0 until minOf(px.size, reading.blockBits.size)) {
            val expected = known[((i % reading.length) + rotation) % reading.length]
            val hit = reading.blockBits[i].toInt() == expected
            if (hit) agreed++
            px[i] = if (hit) Color.argb(150, 34, 211, 238) else Color.argb(30, 120, 120, 140)
        }
        map.setPixels(px, 0, cols, 0, 0, cols, rows)
        val percent = if (px.isEmpty()) 0 else agreed * 100 / px.size
        return map to percent
    }

    // ------------------------------------------------------------------ search

    /** Covers the whole frame, both channels, every grid alignment. */
    private fun search(bitmap: Bitmap): Reading? {
        var best: Reading? = null
        for (tile in tiles(bitmap.width, bitmap.height)) {
            val image = ChromaPlanes.read(bitmap, tile.left, tile.top, tile.width, tile.height) ?: continue
            best = better(best, analyseTile(image, tile.rect(bitmap.width, bitmap.height)))
            if (best != null && best.strength >= 5.0) return best
        }
        return best
    }

    /**
     * The same search over a file too large to hold in memory at once.
     *
     * Shrinking a picture would destroy the very mark being looked for, so the
     * file is read at full size in pieces instead. This is what lets a forty
     * megapixel photograph be checked properly on a phone.
     */
    fun scanFileTiles(path: String): Reading? {
        val decoder = openRegionDecoder(path) ?: return null
        var best: Reading? = null
        try {
            for (tile in tiles(decoder.width, decoder.height)) {
                val rect = android.graphics.Rect(
                    tile.left, tile.top, tile.left + tile.width, tile.top + tile.height,
                )
                val options = android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val piece = runCatching { decoder.decodeRegion(rect, options) }.getOrNull() ?: continue
                val image = ChromaPlanes.read(piece, 0, 0, piece.width, piece.height)
                if (image != null) {
                    best = better(
                        best,
                        analyseTile(image, tile.rect(decoder.width, decoder.height)),
                    )
                }
                piece.recycle()
                if (best != null && best.strength >= 5.0) return best
            }
        } finally {
            runCatching { decoder.recycle() }
        }
        return best
    }

    @Suppress("DEPRECATION")
    private fun openRegionDecoder(path: String): android.graphics.BitmapRegionDecoder? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.graphics.BitmapRegionDecoder.newInstance(path)
        } else {
            android.graphics.BitmapRegionDecoder.newInstance(path, false)
        }
    }.getOrNull()

    private fun better(current: Reading?, candidates: List<Reading>): Reading? {
        var best = current
        for (r in candidates) if (best == null || r.strength > best.strength) best = r
        return best
    }

    /** Every alignment and both colour channels of one piece of the picture. */
    private fun analyseTile(image: ChromaPlanes.Image, region: RectF): List<Reading> {
        val found = mutableListOf<Reading>()
        for (channel in 0..1) {
            val plane = if (channel == 0) image.u else image.v
            val ll = ChromaPlanes.approximation(plane)
            for (offset in ALIGNMENTS) {
                val readings = readAll(ll, offset.first, offset.second, region, channel)
                found += readings
                if (readings.any { it.strength >= 5.0 }) return found
            }
        }
        return found
    }

    private val ALIGNMENTS: List<Pair<Int, Int>> = buildList {
        add(0 to 0)
        for (y in 0 until BLOCK) for (x in 0 until BLOCK) if (x != 0 || y != 0) add(x to y)
    }

    private class Tile(val left: Int, val top: Int, val width: Int, val height: Int) {
        fun rect(fullWidth: Int, fullHeight: Int) = RectF(
            left.toFloat() / fullWidth,
            top.toFloat() / fullHeight,
            (left + width).toFloat() / fullWidth,
            (top + height).toFloat() / fullHeight,
        )
    }

    /** Whole-image coverage in pieces small enough to stay quick. */
    private fun tiles(w: Int, h: Int): List<Tile> {
        if (w <= MAX_TILE && h <= MAX_TILE) return listOf(Tile(0, 0, w, h))
        val out = mutableListOf<Tile>()
        var top = 0
        while (top < h) {
            val th = min(MAX_TILE, h - top)
            var left = 0
            while (left < w) {
                val tw = min(MAX_TILE, w - left)
                if (tw >= 128 && th >= 128) out += Tile(left, top, tw, th)
                left += MAX_TILE
            }
            top += MAX_TILE
        }
        return out
    }

    /**
     * Reads every candidate period in one walk of the blocks.
     *
     * The decision per bit is taken against the picture's own overall rate, not
     * against a flat half, because a picture can lean one way on its own; only
     * a deliberate mark makes particular positions lean differently from the rest.
     */
    private fun readAll(
        ll: ChromaPlanes.Plane,
        offsetX: Int,
        offsetY: Int,
        region: RectF,
        channel: Int,
    ): List<Reading> {
        val rows = (ll.height - offsetY) / BLOCK
        val cols = (ll.width - offsetX) / BLOCK
        if (rows < 4 || cols < 4) return emptyList()

        val blocks = rows * cols
        val bits = ByteArray(blocks)
        var index = 0
        var ones = 0
        for (by in 0 until rows) {
            val y0 = offsetY + by * BLOCK
            for (bx in 0 until cols) {
                val x0 = offsetX + bx * BLOCK
                var pos = 1
                var peak = -1.0
                for (k in 1 until BLOCK * BLOCK) {
                    val v = abs(ll[y0 + k / BLOCK, x0 + k % BLOCK])
                    if (v > peak) { peak = v; pos = k }
                }
                val value = abs(ll[y0 + pos / BLOCK, x0 + pos % BLOCK])
                val bit: Byte = if (value % QUANT > QUANT / 2) 1 else 0
                bits[index++] = bit
                if (bit.toInt() == 1) ones++
            }
        }

        val rate = ones.toDouble() / blocks
        if (rate < 0.02 || rate > 0.98) return emptyList()
        val out = mutableListOf<Reading>()

        for (length in LENGTHS) {
            if (blocks / length < MIN_VOTES) continue
            // When the grid width divides the period, slot i only ever samples
            // columns congruent to i. Then any vertical structure in the scene,
            // a door frame, the edge of a television, reads as a strong
            // "repeating mark": that is exactly how a photograph of a TV on a
            // wall came back as watermarked. In that geometry image structure
            // and a mark are mathematically indistinguishable, so the reading
            // is not taken at all.
            if (cols % length == 0) continue
            val slotOnes = IntArray(length)
            val slotTotal = IntArray(length)
            for (i in 0 until blocks) {
                val slot = i % length
                slotTotal[slot]++
                if (bits[i].toInt() == 1) slotOnes[slot]++
            }
            var z = 0.0
            val decoded = IntArray(length)
            for (i in 0 until length) {
                val mean = slotOnes[i].toDouble() / slotTotal[i]
                decoded[i] = if (mean > rate) 1 else 0
                val se = sqrt(max(rate * (1 - rate), 1e-6) / slotTotal[i])
                z += abs(mean - rate) / se
            }
            out += Reading(
                length = length,
                bits = decoded,
                strength = z / length,
                votesPerBit = slotTotal.min(),
                region = region,
                channel = channel,
                blockBits = bits,
                gridCols = cols,
                gridRows = rows,
            )
        }
        return out
    }

    // --------------------------------------------------------------------- lsb

    class LsbReport(val score: Float, val region: RectF?)

    /**
     * Looks at the last bit of every pixel across the whole frame, in pieces, so
     * data hidden in one part of the picture is still noticed.
     */
    private fun lsbUniformity(bitmap: Bitmap): LsbReport {
        val w = bitmap.width
        val h = bitmap.height
        val cols = if (w > 1024) 3 else 1
        val rows = if (h > 1024) 3 else 1
        var worst = 0f
        var worstRegion: RectF? = null
        var any = false

        for (ry in 0 until rows) {
            for (rx in 0 until cols) {
                val left = w * rx / cols
                val top = h * ry / rows
                val tw = w * (rx + 1) / cols - left
                val th = h * (ry + 1) / rows - top
                if (tw < 32 || th < 32) continue
                val score = lsbScore(bitmap, left, top, tw, th) ?: continue
                any = true
                if (score > worst) {
                    worst = score
                    worstRegion = RectF(
                        left.toFloat() / w, top.toFloat() / h,
                        (left + tw).toFloat() / w, (top + th).toFloat() / h,
                    )
                }
            }
        }
        return if (!any) LsbReport(0f, null) else LsbReport(worst, worstRegion)
    }

    /** Samples full-size pieces of a large file rather than shrinking it. */
    private fun lsbFromFile(path: String): LsbReport {
        val decoder = openRegionDecoder(path) ?: return LsbReport(0f, null)
        var worst = 0f
        var worstRegion: RectF? = null
        try {
            val w = decoder.width
            val h = decoder.height
            val size = 512
            for (ry in 0 until 3) {
                for (rx in 0 until 3) {
                    val left = ((w - size).coerceAtLeast(0) * rx) / 2
                    val top = ((h - size).coerceAtLeast(0) * ry) / 2
                    val rect = android.graphics.Rect(
                        left, top, (left + size).coerceAtMost(w), (top + size).coerceAtMost(h),
                    )
                    if (rect.width() < 64 || rect.height() < 64) continue
                    val piece = runCatching { decoder.decodeRegion(rect, null) }.getOrNull() ?: continue
                    val score = lsbScore(piece, 0, 0, piece.width, piece.height)
                    piece.recycle()
                    if (score != null && score > worst) {
                        worst = score
                        worstRegion = RectF(
                            rect.left.toFloat() / w, rect.top.toFloat() / h,
                            rect.right.toFloat() / w, rect.bottom.toFloat() / h,
                        )
                    }
                }
            }
        } finally {
            runCatching { decoder.recycle() }
        }
        return LsbReport(worst, worstRegion)
    }

    private fun lsbScore(bitmap: Bitmap, left: Int, top: Int, w: Int, h: Int): Float? {
        val stepX = max(1, w / 320)
        val stepY = max(1, h / 320)
        val hist = IntArray(256)
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            bitmap.getPixels(row, 0, w, left, top + y, w, 1)
            var x = 0
            while (x < w) {
                hist[Color.green(row[x])]++
                x += stepX
            }
            y += stepY
        }
        var matched = 0
        var pairs = 0
        for (i in 0 until 128) {
            val a = hist[2 * i]
            val b = hist[2 * i + 1]
            val sum = a + b
            if (sum < 40) continue
            pairs++
            val expected = sum / 2.0
            if (abs(a - expected) / expected < 0.06) matched++
        }
        return if (pairs < 8) null else matched.toFloat() / pairs
    }

    /**
     * True when the top and bottom halves of the region, decoded independently,
     * carry the same payload.
     *
     * A real watermark is repeated across the whole frame, so both halves must
     * read the same. A pattern produced by the scene itself, an edge, a
     * gradient, texture, changes with the content, and the halves disagree.
     * This is the difference between a mark and a coincidence.
     */
    private fun halvesAgree(reading: Reading): Boolean {
        val cols = reading.gridCols
        val rows = reading.gridRows
        val len = reading.length
        if (cols <= 0 || rows < 8) return false
        val topCount = (rows / 2) * cols
        val total = reading.blockBits.size
        if (topCount / len < 6 || (total - topCount) / len < 6) return false

        fun decode(from: Int, to: Int): IntArray? {
            val ones = IntArray(len)
            val slots = IntArray(len)
            var one = 0
            for (i in from until to) {
                val slot = i % len
                slots[slot]++
                if (reading.blockBits[i].toInt() == 1) { ones[slot]++; one++ }
            }
            val rate = one.toDouble() / (to - from)
            if (rate < 0.02 || rate > 0.98) return null
            return IntArray(len) { if (slots[it] == 0) 0 else if (ones[it].toDouble() / slots[it] > rate) 1 else 0 }
        }

        val top = decode(0, topCount) ?: return false
        val bottom = decode(topCount, total) ?: return false
        val agree = top.indices.count { top[it] == bottom[it] }.toFloat() / len
        return agree >= 0.85f
    }

    // ----------------------------------------------------------------- helpers

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
