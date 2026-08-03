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
        Stage("pixels", "Measuring the pixels"),
        Stage("weigh", "Weighing the evidence"),
    )

    @Volatile
    var lastResult: DetectionResult? = null
        private set

    fun analyse(file: File, bitmap: Bitmap?): Flow<OperationProgress> = progressFlow(
        title = "Checking for AI generation",
        stages = stages,
    ) {
        val t0 = System.nanoTime()

        val scan = stage("provenance") { ProvenanceScanner(exifTool).scan(file) }
        update("provenance", 1f, "${scan.tags.size} tags read")

        val forensics = if (bitmap != null) {
            stage("pixels") { ForensicAnalyzer.analyse(bitmap) }
        } else {
            skip("pixels", "no image to measure")
            ForensicAnalyzer.Analysis(emptyList(), null, emptyList())
        }

        stage("weigh") {
            val evidence = scan.evidence + forensics.evidence
            val proof = evidence.firstOrNull { it.decisive }

            val forensicScore = forensics.evidence.sumOf { it.weight.toDouble() }.toFloat()
            val provenanceScore = scan.evidence.filterNot { it.decisive }
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

            val note = when (verdict) {
                Verdict.CONFIRMED_AI ->
                    "Based on a declaration inside the file, not on a guess about the pixels."
                Verdict.CONFIRMED_CAPTURE ->
                    "Based on a signed capture credential, not on a guess about the pixels."
                else ->
                    "No provenance data survived in this file, so this is an estimate from " +
                        "pixel statistics alone. Screenshots, heavy edits and messaging apps " +
                        "all push these numbers around. Treat it as a hint, not a finding."
            }

            lastResult = DetectionResult(
                verdict = verdict,
                aiScore = score,
                confidence = confidence,
                evidence = evidence.sortedByDescending { kotlin.math.abs(it.weight) },
                heatmap = forensics.heatmap,
                hotspots = if (verdict == Verdict.CONFIRMED_CAPTURE) emptyList() else forensics.hotspots,
                modelAccuracyNote = note,
                elapsedMs = (System.nanoTime() - t0) / 1_000_000,
            )
            update("weigh", 1f, "${evidence.size} pieces of evidence")
        }
    }
}
