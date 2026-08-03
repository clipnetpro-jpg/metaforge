package com.metaforge.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaforge.ai.AiDetector
import com.metaforge.ai.DetectionResult
import com.metaforge.ai.Evidence
import com.metaforge.ai.EvidenceKind
import com.metaforge.ai.Verdict
import com.metaforge.core.OperationProgress
import com.metaforge.data.MediaAccess
import com.metaforge.ui.Engine
import com.metaforge.ui.components.EvidenceOverlay
import com.metaforge.ui.components.LiveProgressPanel
import com.metaforge.ui.components.ScanningOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Is this image generated?
 *
 * The screen is built around the difference between proof and estimate. When
 * the file declares its origin the verdict is stated flatly. When it does not,
 * the app says so in the same breath as the number, because a percentage with
 * no provenance behind it is the easiest way to mislead someone.
 */
@Composable
fun DetectScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = remember { Engine.media(context) }

    var staged by remember { mutableStateOf<MediaAccess.Staged?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var heatmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var progress by remember { mutableStateOf<OperationProgress?>(null) }
    var result by remember { mutableStateOf<DetectionResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showHeatmap by remember { mutableStateOf(true) }

    val pick = rememberFilePicker(IMAGE_ONLY) { uri ->
        busy = true
        result = null
        progress = null
        heatmap = null
        scope.launch {
            runCatching {
                val s = withContext(Dispatchers.IO) { media.stage(uri) }
                staged = s
                preview = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(s.workingCopy.absolutePath, opts)?.asImageBitmap()
                }
            }.onFailure { message = it.message ?: "could not open that image" }
            busy = false
        }
    }

    fun run() {
        val s = staged ?: return
        val et = Engine.exifTool(context)
        if (et == null) {
            message = "engine unavailable: ${Engine.failure()}"
            return
        }
        busy = true
        message = null
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(s.workingCopy.absolutePath)
            }
            val detector = AiDetector(et)
            runCatching {
                detector.analyse(s.workingCopy, bitmap)
                    .flowOn(Dispatchers.IO)
                    .collect { progress = it }
            }.onFailure { message = it.message ?: "analysis failed" }
            result = detector.lastResult
            heatmap = result?.heatmap?.asImageBitmap()
            busy = false
        }
    }

    ScreenScaffold(
        title = "Detect AI images",
        subtitle = staged?.displayName ?: "no image chosen",
        onBack = onBack,
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            FileSlot(
                label = "Image",
                fileName = staged?.displayName,
                detail = staged?.let { humanSize(it.sizeBytes) },
                enabled = !busy,
                onPick = pick,
            )

            preview?.let { image ->
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                ) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ScanningOverlay(
                        active = busy && progress != null,
                        progress = progress?.overallFraction ?: 0f,
                        modifier = Modifier.matchParentSize(),
                    )
                    result?.let { r ->
                        EvidenceOverlay(
                            hotspots = r.hotspots,
                            heatmap = heatmap,
                            modifier = Modifier.matchParentSize(),
                            showHeatmap = showHeatmap,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            RunButton(if (busy) "Analysing..." else "Analyse this image", !busy && staged != null) { run() }

            progress?.let {
                Spacer(Modifier.height(16.dp))
                LiveProgressPanel(it)
            }

            result?.let { r ->
                Spacer(Modifier.height(16.dp))
                VerdictCard(r)
                if (r.heatmap != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(
                            "Show suspicion heatmap",
                            color = Muted,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f).padding(top = 12.dp),
                        )
                        Switch(
                            checked = showHeatmap,
                            onCheckedChange = { showHeatmap = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Ink, checkedTrackColor = Accent,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                r.evidence.forEach { EvidenceRow(it) }
            }

            message?.let {
                Spacer(Modifier.height(12.dp))
                InfoCard("Result", it, Bad)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun VerdictCard(r: DetectionResult) {
    val (label, tint) = when (r.verdict) {
        Verdict.CONFIRMED_AI -> "AI generated, confirmed by the file itself" to Bad
        Verdict.LIKELY_AI -> "Probably AI generated" to Warn
        Verdict.UNCERTAIN -> "Cannot tell" to Muted
        Verdict.LIKELY_AUTHENTIC -> "Probably a real photograph" to Good
        Verdict.CONFIRMED_CAPTURE -> "Camera capture, confirmed by a credential" to Good
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Spacer(Modifier.height(10.dp))
            if (r.isProven) {
                Text(
                    "Proof, not a guess.",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            } else {
                Text(
                    "AI score ${r.aiScore} / 100, confidence ${r.confidence}%",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { r.aiScore / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = tint,
                    trackColor = Color.White.copy(alpha = 0.08f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(r.modelAccuracyNote, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text("${r.elapsedMs} ms", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun EvidenceRow(e: Evidence) {
    val tint = when {
        e.decisive -> Bad
        e.weight > 0 -> Warn
        else -> Good
    }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row {
                Text(
                    when (e.kind) {
                        EvidenceKind.PROVENANCE -> "PROVENANCE"
                        EvidenceKind.MODEL -> "MODEL"
                        EvidenceKind.FORENSIC -> "PIXELS"
                    },
                    color = tint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    (if (e.weight > 0) "+" else "") + "%.2f".format(e.weight),
                    color = tint,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(e.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(e.explanation, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            e.measurement?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
