package com.metaforge.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * LaMa inpainting through ONNX Runtime, running entirely on the device.
 *
 * Classical inpainting propagates neighbouring colour inward, which is why it
 * smudges anything larger than a scratch. LaMa uses Fourier convolutions with a
 * global receptive field, so it reconstructs periodic structure (brick, grass,
 * fabric, skin) instead of averaging it away.
 *
 * The network works at a fixed resolution, so instead of downscaling the whole
 * photo the engine crops a context window around the painted area, repairs that
 * at native detail, and pastes only the masked pixels back. A 48 megapixel photo
 * therefore keeps its full resolution everywhere except the region being fixed.
 */
class LamaInpainter private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputSize: Int,
) : Closeable {

    data class Tile(val rect: Rect, val elapsedMs: Long)

    /**
     * @param onTile called after each processed window, for live progress
     */
    fun inpaint(
        image: Bitmap,
        mask: Bitmap,
        contextMargin: Float = 0.45f,
        onTile: ((index: Int, total: Int, tile: Tile) -> Unit)? = null,
    ): Bitmap {
        val regions = maskRegions(mask)
        require(regions.isNotEmpty()) { "nothing was painted" }

        val result = image.copy(Bitmap.Config.ARGB_8888, true)
        regions.forEachIndexed { i, bbox ->
            val t0 = System.nanoTime()
            val window = expand(bbox, image.width, image.height, contextMargin)
            val patch = Bitmap.createBitmap(image, window.left, window.top, window.width(), window.height())
            val patchMask = Bitmap.createBitmap(mask, window.left, window.top, window.width(), window.height())

            val repaired = runModel(patch, patchMask)

            // Only masked pixels are replaced; everything else stays original.
            val scaled = Bitmap.createScaledBitmap(repaired, window.width(), window.height(), true)
            compose(result, scaled, patchMask, window)

            patch.recycle(); patchMask.recycle(); repaired.recycle(); scaled.recycle()
            onTile?.invoke(i + 1, regions.size, Tile(window, (System.nanoTime() - t0) / 1_000_000))
        }
        return result
    }

    private fun runModel(patch: Bitmap, patchMask: Bitmap): Bitmap {
        val n = inputSize
        val img = Bitmap.createScaledBitmap(patch, n, n, true)
        val msk = Bitmap.createScaledBitmap(patchMask, n, n, false)

        val pixels = IntArray(n * n)
        img.getPixels(pixels, 0, n, 0, 0, n, n)
        val mpixels = IntArray(n * n)
        msk.getPixels(mpixels, 0, n, 0, 0, n, n)

        val imgBuf = FloatBuffer.allocate(3 * n * n)
        for (c in 0 until 3) {
            for (p in pixels) {
                val v = when (c) {
                    0 -> Color.red(p); 1 -> Color.green(p); else -> Color.blue(p)
                }
                imgBuf.put(v / 255f)
            }
        }
        imgBuf.rewind()

        val maskBuf = FloatBuffer.allocate(n * n)
        for (p in mpixels) maskBuf.put(if (Color.red(p) > 127) 1f else 0f)
        maskBuf.rewind()

        val imageName = session.inputNames.firstOrNull { it.contains("image", true) }
            ?: session.inputNames.first()
        val maskName = session.inputNames.firstOrNull { it.contains("mask", true) }
            ?: session.inputNames.last()

        OnnxTensor.createTensor(env, imgBuf, longArrayOf(1, 3, n.toLong(), n.toLong())).use { it0 ->
            OnnxTensor.createTensor(env, maskBuf, longArrayOf(1, 1, n.toLong(), n.toLong())).use { it1 ->
                session.run(mapOf(imageName to it0, maskName to it1)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val raw = out[0].value as Array<Array<Array<FloatArray>>>
                    val plane = raw[0]
                    // Some exports emit 0..1, others 0..255. Detect and normalise.
                    var maxV = 0f
                    for (c in 0 until 3) for (y in 0 until n) for (x in 0 until n)
                        if (plane[c][y][x] > maxV) maxV = plane[c][y][x]
                    val scale = if (maxV > 1.5f) 1f else 255f

                    val outPixels = IntArray(n * n)
                    for (y in 0 until n) for (x in 0 until n) {
                        val r = (plane[0][y][x] * scale).toInt().coerceIn(0, 255)
                        val g = (plane[1][y][x] * scale).toInt().coerceIn(0, 255)
                        val b = (plane[2][y][x] * scale).toInt().coerceIn(0, 255)
                        outPixels[y * n + x] = Color.rgb(r, g, b)
                    }
                    img.recycle(); msk.recycle()
                    return Bitmap.createBitmap(outPixels, n, n, Bitmap.Config.ARGB_8888)
                }
            }
        }
    }

    /** Copies repaired pixels into [dest] only where the mask is white. */
    private fun compose(dest: Bitmap, repaired: Bitmap, mask: Bitmap, window: Rect) {
        val w = window.width(); val h = window.height()
        val rp = IntArray(w * h); repaired.getPixels(rp, 0, w, 0, 0, w, h)
        val mp = IntArray(w * h); mask.getPixels(mp, 0, w, 0, 0, w, h)
        val dp = IntArray(w * h); dest.getPixels(dp, 0, w, window.left, window.top, w, h)
        for (i in dp.indices) if (Color.red(mp[i]) > 127) dp[i] = rp[i]
        dest.setPixels(dp, 0, w, window.left, window.top, w, h)
    }

    /** Connected painted areas, merged when they overlap, as bounding boxes. */
    private fun maskRegions(mask: Bitmap): List<Rect> {
        val w = mask.width; val h = mask.height
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.red(px[y * w + x]) > 127) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return emptyList()
        return listOf(Rect(minX, minY, maxX + 1, maxY + 1))
    }

    private fun expand(r: Rect, w: Int, h: Int, margin: Float): Rect {
        val padX = (r.width() * margin).toInt().coerceAtLeast(32)
        val padY = (r.height() * margin).toInt().coerceAtLeast(32)
        var left = max(0, r.left - padX)
        var top = max(0, r.top - padY)
        var right = min(w, r.right + padX)
        var bottom = min(h, r.bottom + padY)
        // Keep it roughly square so the resize to the model input does not distort.
        val side = max(right - left, bottom - top)
        val cx = (left + right) / 2; val cy = (top + bottom) / 2
        left = max(0, cx - side / 2); top = max(0, cy - side / 2)
        right = min(w, left + side); bottom = min(h, top + side)
        return Rect(left, top, right, bottom)
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "LamaInpainter"

        fun load(context: Context, model: ModelManager.Model, inputSize: Int = 512): LamaInpainter? {
            val file = ModelManager.fileFor(context, model)
            if (!file.exists()) return null
            return runCatching {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    addNnapi()
                }
                LamaInpainter(env, env.createSession(file.absolutePath, opts), inputSize)
            }.onFailure { Log.e(TAG, "failed to load ${model.id}", it) }.getOrNull()
        }
    }
}
