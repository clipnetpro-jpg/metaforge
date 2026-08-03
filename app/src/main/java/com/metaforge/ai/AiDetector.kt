package com.metaforge.ai

import android.graphics.Bitmap
import com.metaforge.core.OperationProgress
import com.metaforge.core.Stage
import com.metaforge.core.progressFlow
import com.metaforge.engine.ExifTool
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * Decides whether an image was generated, and shows its working.
 *
 * The layers are deliberately not equal. Provenance is proof: if the file says
 * a model made it, that ends the discussion and the pixel statistics are
 * reported but not allowed to argue. Only when nothing declares itself do the
 * forensic measurements set the score, and then the app says out loud that it
 * is estimating from pixels, because that is a weaker claim and the user
 * deserves to know the difference.
 */
class AiDetector(private val exifTool: ExifTool) {

    private val stages = listOf(
        Stage("provenance", "Reading provenance and credentials"),
        Stage("watermark", "Looking for an invisible watermark"),
        Stage("pixels", "Measuring the pixels"),
        Stage("weigh", "Weighing the evidence"),
    )

    @Volatile
    var lastResult: DetectionResult? = null
        private set

    /** Whether the last analysis found something the user could choose to remove. */
    @Volatile
    var lastRemovable: Boolean = false
        private set

    private companion object {
        /**
         * Said out loud rather than buried: some watermarks are unreadable by
         * design, and a detector that stays quiet about that is misleading.
         */
        const val INVISIBLE_LIMIT =
            "Nothing in this file declares its origin and no readable watermark is present, so " +
                "this score comes from pixel statistics alone. Some generators, Google's SynthID " +
                "among them, hide a mark that only its issuer can verify: no app can read it, so " +
                "a clean result here never proves an image is real. Screenshots, heavy edits and " +
                "messaging apps also move these numbers."
    }

    /**
     * @param bitmapIsFullSize false when [bitmap] had to be shrunk to fit in
     *        memory. A shrunk picture cannot carry a hidden mark, so in that
     *        case the file itself is read at full size, piece by piece.
     */
    fun analyse(
        file: File,
        bitmap: Bitmap?,
        bitmapIsFullSize: Boolean = true,
    ): Flow<OperationProgress> = progressFlow(
        title = "Checking for AI generation",
        stages = stages,
    ) {
        val t0 = System.nanoTime()

        val scan = stage("provenance") { ProvenanceScanner(exifTool).scan(file) }
        update("provenance", 1f, "${scan.tags.size} tags read")

        val watermark = if (bitmap != null && bitmapIsFullSize) {
            stage("watermark") { WatermarkScanner.scan(bitmap) }
        } else if (file.exists()) {
            stage("watermark") { WatermarkScanner.scanFile(file.absolutePath) }
        } else {
            skip("watermark", "no image to read")
            WatermarkScanner.Result(emptyList(), null, null, emptyList(), false)
        }
        watermark.identified?.let { update("watermark", 1f, "$it watermark recovered") }

        val forensics = if (bitmap != null) {
            stage("pixels") { ForensicAnalyzer.analyse(bitmap) }
        } else {
            skip("pixels", "no image to measure")
            ForensicAnalyzer.Analysis(emptyList(), null, emptyList())
        }

        stage("weigh") {
            val evidence = scan.evidence + watermark.evidence + forensics.evidence
            val proof = evidence.firstOrNull { it.decisive }

            val forensicScore = forensics.evidence.sumOf { it.weight.toDouble() }.toFloat()
            val provenanceScore = (scan.evidence + watermark.evidence).filterNot { it.decisive }
                .sumOf { it.weight.toDouble() }.toFloat()

            val score: Int
            val verdict: Verdict
            val confidence: Int
            when {
                proof != null && proof.weight > 0 -> {
                    score = 100
                    verdict = Verdict.CONFIRMED_AI
                    confidence = 99
                }
                proof != null -> {
                    score = 0
                    verdict = Verdict.CONFIRMED_CAPTURE
                    confidence = 96
                }
                else -> {
                    val raw = provenanceScore + forensicScore
                    score = (50 + raw * 45).toInt().coerceIn(0, 100)
                    verdict = when {
                        score >= 72 -> Verdict.LIKELY_AI
                        score <= 30 -> Verdict.LIKELY_AUTHENTIC
                        else -> Verdict.UNCERTAIN
                    }
                    // Confidence follows how much evidence there is and how far
                    // it leans, never higher than a pixel-only judgement earns.
                    val lean = kotlin.math.abs(score - 50) / 50f
                    confidence = (30 + lean * 40 + evidence.size * 2).toInt().coerceIn(20, 75)
                }
            }

            val note = when {
                watermark.identified != null ->
                    "Read out of the pixels themselves. A watermark like this survives a metadata " +
                        "wipe, a crop and a screenshot, so this is the strongest kind of answer."
                verdict == Verdict.CONFIRMED_AI ->
                    "Based on a declaration inside the file, not on a guess about the pixels."
                verdict == Verdict.CONFIRMED_CAPTURE ->
                    "Based on a signed capture credential, not on a guess about the pixels."
                else -> INVISIBLE_LIMIT
            }

            lastRemovable = watermark.removable
            lastResult = DetectionResult(
                verdict = verdict,
                aiScore = score,
                confidence = confidence,
                evidence = evidence.sortedByDescending { kotlin.math.abs(it.weight) },
                heatmap = forensics.heatmap,
                markMap = watermark.coverage,
                markCoverage = watermark.coveragePercent,
                hotspots = watermark.marks +
                    if (verdict == Verdict.CONFIRMED_CAPTURE) emptyList() else forensics.hotspots,
                modelAccuracyNote = note,
                elapsedMs = (System.nanoTime() - t0) / 1_000_000,
            )
            update("weigh", 1f, "${evidence.size} pieces of evidence")
        }
    }
}
