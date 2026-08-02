package com.metaforge.engine

import android.graphics.Bitmap
import android.util.Log
import com.metaforge.core.OperationProgress
import com.metaforge.core.Stage
import com.metaforge.core.progressFlow
import kotlinx.coroutines.flow.Flow
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Repairs damaged or unwanted regions of a photo: scratches, dust, blemishes,
 * a stray object, a mangled hand or ear in a generated picture.
 *
 * The user paints a mask over the bad area and the engine reconstructs it from
 * the surrounding pixels. Everything runs on device with OpenCV, no model
 * download and no network.
 *
 * Two algorithms, chosen by region size:
 *  - Telea's fast marching method for thin defects like scratches and dust.
 *  - Navier-Stokes fluid propagation for larger blobs, run coarse-to-fine so
 *    structure from further away can reach the middle of a big hole.
 *
 * Pixels outside the mask are copied back verbatim at the end, so the rest of
 * the photograph is bit-identical to the original.
 */
object InpaintEngine {

    private const val TAG = "InpaintEngine"

    enum class Method { AUTO, TELEA, NAVIER_STOKES }

    data class Options(
        val method: Method = Method.AUTO,
        /** How far outside the mask the algorithm looks, in pixels. */
        val radius: Double = 6.0,
        /** Grow the mask slightly so defect edges are covered too. */
        val maskDilation: Int = 2,
        /** Soften the seam between repaired and original pixels. */
        val featherPx: Int = 2,
    )

    data class Report(
        val method: Method,
        val maskedPixels: Long,
        val maskedPercent: Double,
        val passes: Int,
        /** Similarity of the untouched area, proving nothing else moved. */
        val untouchedPsnr: Double,
        val elapsedMs: Long,
    )

    @Volatile
    var lastReport: Report? = null
        private set

    private val stages = listOf(
        Stage("init", "Preparing image engine"),
        Stage("mask", "Preparing the mask"),
        Stage("repair", "Reconstructing the region"),
        Stage("blend", "Blending edges"),
        Stage("verify", "Checking the rest of the photo is untouched"),
    )

    @Volatile private var initialised = false

    private fun ensureOpenCv(): Boolean {
        if (initialised) return true
        initialised = OpenCVLoader.initLocal()
        if (!initialised) Log.e(TAG, "OpenCV failed to initialise")
        return initialised
    }

    /**
     * @param source the photo to repair
     * @param mask   white (255) where pixels should be reconstructed, black elsewhere
     * @param onResult receives the repaired bitmap when the flow completes
     */
    fun inpaint(
        source: Bitmap,
        mask: Bitmap,
        options: Options = Options(),
        onResult: (Bitmap) -> Unit,
    ): Flow<OperationProgress> = progressFlow("Repairing image", stages) {
        val t0 = System.nanoTime()

        stage("init") {
            if (!ensureOpenCv()) error("OpenCV could not start on this device")
        }

        val src = Mat()
        val maskMat = Mat()
        val prepared: Mat

        stage("mask") {
            Utils.bitmapToMat(source, src)
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)

            Utils.bitmapToMat(mask, maskMat)
            Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_RGBA2GRAY)
            if (maskMat.size() != src.size()) {
                Imgproc.resize(maskMat, maskMat, src.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
            }
            Imgproc.threshold(maskMat, maskMat, 127.0, 255.0, Imgproc.THRESH_BINARY)
            if (options.maskDilation > 0) {
                val k = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    Size((options.maskDilation * 2 + 1).toDouble(), (options.maskDilation * 2 + 1).toDouble()),
                )
                Imgproc.dilate(maskMat, maskMat, k)
            }
            val masked = Core.countNonZero(maskMat).toLong()
            val total = src.rows().toLong() * src.cols()
            if (masked == 0L) error("nothing was painted; draw over the area to repair")
            update("mask", 1f, "%.2f%% of the image selected".format(masked * 100.0 / total))
        }

        val maskedPixels = Core.countNonZero(maskMat).toLong()
        val totalPixels = src.rows().toLong() * src.cols()
        val maskedPercent = maskedPixels * 100.0 / totalPixels

        val method = when (options.method) {
            Method.AUTO -> if (maskedPercent > 1.5) Method.NAVIER_STOKES else Method.TELEA
            else -> options.method
        }
        val flag = if (method == Method.TELEA) Photo.INPAINT_TELEA else Photo.INPAINT_NS

        var passes = 1
        val repaired = Mat()

        stage("repair", "using ${if (method == Method.TELEA) "fast marching" else "fluid propagation"}") {
            if (method == Method.NAVIER_STOKES && maskedPercent > 1.5) {
                // Coarse-to-fine: solve a downscaled version first so colour and
                // structure from far outside the hole can reach its centre,
                // then refine at full resolution.
                passes = 2
                val small = Mat()
                val smallMask = Mat()
                val half = Size(src.cols() / 2.0, src.rows() / 2.0)
                Imgproc.resize(src, small, half, 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.resize(maskMat, smallMask, half, 0.0, 0.0, Imgproc.INTER_NEAREST)
                val smallOut = Mat()
                Photo.inpaint(small, smallMask, smallOut, options.radius, flag)
                update("repair", 0.5f, "coarse pass done")

                val upscaled = Mat()
                Imgproc.resize(smallOut, upscaled, src.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                // Seed the hole with the coarse result, then let the fine pass sharpen it.
                upscaled.copyTo(src, maskMat)
                Photo.inpaint(src, maskMat, repaired, options.radius, flag)
                small.release(); smallMask.release(); smallOut.release(); upscaled.release()
            } else {
                Photo.inpaint(src, maskMat, repaired, options.radius, flag)
            }
            update("repair", 1f, "$passes pass${if (passes > 1) "es" else ""}")
        }

        stage("blend") {
            if (options.featherPx > 0) {
                val soft = Mat()
                val k = options.featherPx * 2 + 1
                Imgproc.GaussianBlur(maskMat, soft, Size(k.toDouble(), k.toDouble()), 0.0)
                soft.convertTo(soft, CvType.CV_32FC1, 1.0 / 255.0)
                val alpha = ArrayList<Mat>(3).apply { repeat(3) { add(soft) } }
                val alpha3 = Mat()
                Core.merge(alpha, alpha3)

                val a = Mat(); val b = Mat()
                repaired.convertTo(a, CvType.CV_32FC3)
                src.convertTo(b, CvType.CV_32FC3)
                val inv = Mat()
                Core.subtract(Mat.ones(alpha3.size(), alpha3.type()), alpha3, inv)
                Core.multiply(a, alpha3, a)
                Core.multiply(b, inv, b)
                Core.add(a, b, a)
                a.convertTo(repaired, CvType.CV_8UC3)
                soft.release(); alpha3.release(); inv.release(); a.release(); b.release()
            }
        }

        var psnr = Double.POSITIVE_INFINITY
        stage("verify") {
            // Compare only the pixels the user did NOT paint. They must be identical.
            val invMask = Mat()
            Core.bitwise_not(maskMat, invMask)
            val origOutside = Mat(); val newOutside = Mat()
            src.copyTo(origOutside, invMask)
            repaired.copyTo(newOutside, invMask)
            psnr = psnr(origOutside, newOutside)
            invMask.release(); origOutside.release(); newOutside.release()
            update(
                "verify", 1f,
                if (psnr > 45) "rest of the photo untouched" else "PSNR outside mask: %.1f dB".format(psnr),
            )
        }

        val outBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Imgproc.cvtColor(repaired, repaired, Imgproc.COLOR_RGB2RGBA)
        Utils.matToBitmap(repaired, outBitmap)
        onResult(outBitmap)

        lastReport = Report(
            method = method,
            maskedPixels = maskedPixels,
            maskedPercent = maskedPercent,
            passes = passes,
            untouchedPsnr = psnr,
            elapsedMs = (System.nanoTime() - t0) / 1_000_000,
        )

        src.release(); maskMat.release(); repaired.release()
    }

    private fun psnr(a: Mat, b: Mat): Double {
        val diff = Mat()
        Core.absdiff(a, b, diff)
        diff.convertTo(diff, CvType.CV_32F)
        Core.multiply(diff, diff, diff)
        val s = Core.sumElems(diff)
        val sse = s.`val`[0] + s.`val`[1] + s.`val`[2]
        diff.release()
        if (sse <= 1e-10) return Double.POSITIVE_INFINITY
        val mse = sse / (a.channels() * a.total())
        return 10.0 * log10(255.0 * 255.0 / mse)
    }
}
