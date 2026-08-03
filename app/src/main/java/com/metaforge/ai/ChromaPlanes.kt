package com.metaforge.ai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

/**
 * The colour planes an invisible watermark lives in, and the way back.
 *
 * Marks are carried in chroma because the eye barely sees it there. Working in
 * the same space the marking tools use is what makes it possible to read a mark
 * exactly, and to take it out again without touching how the picture looks.
 */
internal object ChromaPlanes {

    class Plane(val width: Int, val height: Int, val data: DoubleArray) {
        operator fun get(y: Int, x: Int): Double = data[y * width + x]
        operator fun set(y: Int, x: Int, v: Double) { data[y * width + x] = v }
    }

    class Image(val width: Int, val height: Int, val y: Plane, val u: Plane, val v: Plane)

    /** Reads a region of the bitmap into Y, U and V planes, cropped to a multiple of four. */
    fun read(bitmap: Bitmap, left: Int = 0, top: Int = 0, w: Int = bitmap.width, h: Int = bitmap.height): Image? {
        val width = w / 4 * 4
        val height = h / 4 * 4
        if (width < 64 || height < 64) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)

        val yp = Plane(width, height, DoubleArray(width * height))
        val up = Plane(width, height, DoubleArray(width * height))
        val vp = Plane(width, height, DoubleArray(width * height))
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p).toDouble()
            val g = Color.green(p).toDouble()
            val b = Color.blue(p).toDouble()
            val luma = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255)
            yp.data[i] = luma.toDouble()
            up.data[i] = (0.492 * (b - luma) + 128.0).roundToInt().coerceIn(0, 255).toDouble()
            vp.data[i] = (0.877 * (r - luma) + 128.0).roundToInt().coerceIn(0, 255).toDouble()
        }
        return Image(width, height, yp, up, vp)
    }

    /** Rebuilds pixels from planes, writing them into [into] at the given origin. */
    fun write(image: Image, into: Bitmap, left: Int = 0, top: Int = 0) {
        val pixels = IntArray(image.width * image.height)
        for (i in pixels.indices) {
            val luma = image.y.data[i]
            val u = image.u.data[i]
            val v = image.v.data[i]
            val r = luma + (v - 128.0) / 0.877
            val b = luma + (u - 128.0) / 0.492
            val g = (luma - 0.299 * r - 0.114 * b) / 0.587
            pixels[i] = Color.rgb(
                r.roundToInt().coerceIn(0, 255),
                g.roundToInt().coerceIn(0, 255),
                b.roundToInt().coerceIn(0, 255),
            )
        }
        into.setPixels(pixels, 0, image.width, left, top, image.width, image.height)
    }

    class Wavelet(val ll: Plane, val lh: Plane, val hl: Plane, val hh: Plane)

    /** One level of the Haar transform. */
    fun forward(p: Plane): Wavelet {
        val w = p.width / 2
        val h = p.height / 2
        val ll = Plane(w, h, DoubleArray(w * h))
        val lh = Plane(w, h, DoubleArray(w * h))
        val hl = Plane(w, h, DoubleArray(w * h))
        val hh = Plane(w, h, DoubleArray(w * h))
        for (y in 0 until h) {
            for (x in 0 until w) {
                val a = p[2 * y, 2 * x]
                val b = p[2 * y, 2 * x + 1]
                val c = p[2 * y + 1, 2 * x]
                val d = p[2 * y + 1, 2 * x + 1]
                ll[y, x] = (a + b + c + d) / 2.0
                lh[y, x] = (a + b - c - d) / 2.0
                hl[y, x] = (a - b + c - d) / 2.0
                hh[y, x] = (a - b - c + d) / 2.0
            }
        }
        return Wavelet(ll, lh, hl, hh)
    }

    fun inverse(w: Wavelet, into: Plane) {
        for (y in 0 until w.ll.height) {
            for (x in 0 until w.ll.width) {
                val ll = w.ll[y, x]
                val lh = w.lh[y, x]
                val hl = w.hl[y, x]
                val hh = w.hh[y, x]
                into[2 * y, 2 * x] = (ll + lh + hl + hh) / 2.0
                into[2 * y, 2 * x + 1] = (ll + lh - hl - hh) / 2.0
                into[2 * y + 1, 2 * x] = (ll - lh + hl - hh) / 2.0
                into[2 * y + 1, 2 * x + 1] = (ll - lh - hl + hh) / 2.0
            }
        }
    }

    /** Approximation band only, for reading. */
    fun approximation(p: Plane): Plane = forward(p).ll
}
