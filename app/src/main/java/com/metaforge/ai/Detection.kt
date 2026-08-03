package com.metaforge.ai

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * What the detector found, and why.
 *
 * Every field here is derived from a measurement. Nothing in this model may be
 * populated with a guess or a stock phrase: if the engine cannot measure it, the
 * evidence item is simply absent. A verdict the app cannot justify is not shown.
 */

enum class Verdict {
    /** A signed manifest or generator tag proves it. No probability involved. */
    CONFIRMED_AI,
    LIKELY_AI,
    UNCERTAIN,
    LIKELY_AUTHENTIC,
    /** Valid C2PA capture credential from a camera. Also proof, not a guess. */
    CONFIRMED_CAPTURE,
}

enum class EvidenceKind {
    /** Cryptographic or declared provenance: C2PA, generator metadata, watermark. */
    PROVENANCE,
    /** A model's opinion about the pixels. */
    MODEL,
    /** Measurable image forensics: compression, noise, frequency. */
    FORENSIC,
}

/**
 * @param weight how much this pushed the verdict, -1 (authentic) to +1 (AI)
 * @param region where in the image, in normalised 0..1 coordinates, if localised
 * @param measurement the raw number behind the claim, shown to the user verbatim
 */
data class Evidence(
    val id: String,
    val kind: EvidenceKind,
    val title: String,
    val explanation: String,
    val weight: Float,
    val measurement: String? = null,
    val region: RectF? = null,
    val decisive: Boolean = false,
)

/**
 * @param heatmap per-pixel suspicion, same aspect ratio as the image, 0..255
 * @param hotspots regions worth drawing a box around, strongest first
 */
data class DetectionResult(
    val verdict: Verdict,
    val aiScore: Int,
    val confidence: Int,
    val evidence: List<Evidence>,
    val heatmap: Bitmap? = null,
    /** Block-by-block picture of where a hidden mark reads, if one was found. */
    val markMap: Bitmap? = null,
    val markCoverage: Int = 0,
    val hotspots: List<Hotspot> = emptyList(),
    val modelAccuracyNote: String,
    val elapsedMs: Long,
) {
    val supporting: List<Evidence> get() = evidence.filter { it.weight > 0 }.sortedByDescending { it.weight }
    val contradicting: List<Evidence> get() = evidence.filter { it.weight < 0 }.sortedBy { it.weight }

    /** True when the verdict rests on proof rather than a probability. */
    val isProven: Boolean
        get() = verdict == Verdict.CONFIRMED_AI || verdict == Verdict.CONFIRMED_CAPTURE
}

/**
 * A localised finding drawn on top of the photo.
 *
 * @param bounds normalised 0..1 rectangle
 * @param score 0..1 suspicion inside this region
 * @param reason short human phrase, e.g. "hand geometry inconsistent"
 */
data class Hotspot(
    val bounds: RectF,
    val score: Float,
    val reason: String,
    val detail: String? = null,
)
