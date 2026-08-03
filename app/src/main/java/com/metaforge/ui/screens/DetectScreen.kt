package com.metaforge.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import android.graphics.Bitmap
import com.metaforge.ai.AiDetector
import com.metaforge.ai.OnlineCheck
import com.metaforge.ai.WatermarkRemover
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
    var removable by remember { mutableStateOf(false) }
    var removeMark by remember { mutableStateOf(true) }
    var scrubDetail by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var cleanReport by remember { mutableStateOf<WatermarkRemover.Report?>(null) }
    var markLayer by remember { mutableStateOf<ImageBitmap?>(null) }
    var showMark by remember { mutableStateOf(true) }
    var online by remember { mutableStateOf(OnlineCheck.load(context)) }
    var onlineSheet by remember { mutableStateOf(false) }
    var onlineOutcome by remember { mutableStateOf<OnlineCheck.Outcome?>(null) }
    var onlineBusy by remember { mutableStateOf(false) }
    var written by remember { mutableStateOf(false) }
    var undoAvailable by remember { mutableStateOf(false) }

    val pick = rememberFilePicker(IMAGE_ONLY) { uri ->
        busy = true
        result = null
        progress = null
        heatmap = null
        scope.launch {
            runCatching {
                val s = withContext(Dispatchers.IO) { media.stage(uri) }
                staged = s
                written = false
                undoAvailable = false
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
            message = "the engine is not ready yet, try again in a moment"
            return
        }
        busy = true
        message = null
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { decodeWithinBudget(s.workingCopy.absolutePath) }
            if (loaded.note != null) message = loaded.note
            val detector = AiDetector(et)
            runCatching {
                detector.analyse(s.workingCopy, loaded.bitmap, loaded.fullSize)
                    .flowOn(Dispatchers.IO)
                    .collect { progress = it }
            }.onFailure { message = it.message ?: "analysis failed" }
            result = detector.lastResult
            removable = detector.lastRemovable
            cleanReport = null
            heatmap = result?.heatmap?.asImageBitmap()
            markLayer = result?.markMap?.asImageBitmap()
            onlineOutcome = null
            busy = false
        }
    }

    fun clean() {
        val s = staged ?: return
        busy = true
        cleaning = true
        message = null
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { decodeWithinBudget(s.workingCopy.absolutePath) }
            val bitmap = loaded.bitmap
            if (bitmap == null) {
                message = "this image could not be opened"
                busy = false; cleaning = false
                return@launch
            }
            if (!loaded.fullSize) {
                message = "this picture is too large to rewrite on this device, " +
                    "so it was only checked, not changed"
                busy = false; cleaning = false
                return@launch
            }
            runCatching {
                WatermarkRemover
                    .clean(bitmap, WatermarkRemover.Options(removeMark, scrubDetail))
                    .flowOn(Dispatchers.IO)
                    .collect { progress = it }
            }.onFailure { message = it.message ?: "cleaning did not finish" }

            val report = WatermarkRemover.lastReport
            cleanReport = report
            if (report != null) {
                withContext(Dispatchers.IO) {
                    saveCleanedInto(context, s.workingCopy, report.cleaned)
                }
                preview = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(s.workingCopy.absolutePath)?.asImageBitmap()
                }
                result = null
                heatmap = null
                removable = false
            }
            busy = false
            cleaning = false
        }
    }

    fun saveBack() {
        val s = staged ?: return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { media.commit(s) }
            if (r.isSuccess) {
                written = true
                undoAvailable = withContext(Dispatchers.IO) { media.hasBackup(s) }
            }
            message = if (r.isSuccess) "saved into " + s.displayName
                      else r.exceptionOrNull()?.message ?: "the file could not be written"
            busy = false
        }
    }

    fun undo() {
        val s = staged ?: return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { media.restore(s) }
            if (r.isSuccess) {
                written = false; undoAvailable = false
                result = null; heatmap = null; markLayer = null; cleanReport = null
                preview = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(s.workingCopy.absolutePath, opts)?.asImageBitmap()
                }
            }
            message = if (r.isSuccess) s.displayName + " is back exactly as it was"
                      else r.exceptionOrNull()?.message ?: "could not undo"
            busy = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { staged?.let { media.cleanup(it) } }
    }

    val exportCopy = rememberExportPicker(
        suggestedName = copyName(staged?.displayName ?: "picture.jpg"),
        mimeType = mimeForName(staged?.displayName ?: "picture.jpg"),
    ) { destination ->
        val s = staged ?: return@rememberExportPicker
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { media.exportTo(s, destination) }
            message = if (r.isSuccess) "a copy was saved where you chose"
                      else r.exceptionOrNull()?.message ?: "the copy could not be written"
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
                            heatmap = if (showMark && markLayer != null) markLayer else heatmap,
                            modifier = Modifier.matchParentSize(),
                            showHeatmap = showHeatmap || (showMark && markLayer != null),
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

                if (markLayer != null) {
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        "Show where the mark is",
                        "Lights up every part of the picture that carries it, " +
                            "${r.markCoverage}% of the frame",
                        showMark,
                    ) { showMark = it }
                }

                Spacer(Modifier.height(12.dp))
                OnlineCard(
                    settings = online,
                    outcome = onlineOutcome,
                    busy = onlineBusy,
                    onConfigure = { onlineSheet = true },
                    onRun = {
                        val file = staged?.workingCopy
                        if (file != null) {
                            onlineBusy = true
                            scope.launch {
                                onlineOutcome = withContext(Dispatchers.IO) {
                                    OnlineCheck.check(online, file)
                                }
                                onlineBusy = false
                            }
                        }
                    },
                )

                if (removable) {
                    Spacer(Modifier.height(16.dp))
                    RemovalCard(
                        removeMark = removeMark,
                        onRemoveMark = { removeMark = it },
                        scrubDetail = scrubDetail,
                        onScrubDetail = { scrubDetail = it },
                        busy = busy,
                        onClean = { clean() },
                    )
                }
            }

            cleanReport?.let { report ->
                Spacer(Modifier.height(16.dp))
                CleanedCard(report)
                Spacer(Modifier.height(10.dp))
                SaveRow(
                    saveLabel = "Save over the original",
                    enabled = !busy,
                    onSaveOver = { saveBack() },
                    onExport = exportCopy,
                )
            }

            if (written) {
                Spacer(Modifier.height(12.dp))
                UndoRow(
                    fileName = staged?.displayName ?: "the picture",
                    hasBackup = undoAvailable,
                    enabled = !busy,
                    onRestore = { undo() },
                )
            }

            message?.let {
                Spacer(Modifier.height(12.dp))
                InfoCard("Result", it, if (it.contains("could not")) Bad else Good)
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (onlineSheet) {
        OnlineSettingsSheet(
            initial = online,
            onDismiss = { onlineSheet = false },
            onSave = {
                OnlineCheck.save(context, it)
                online = it
                onlineSheet = false
            },
        )
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

@Composable
private fun RemovalCard(
    removeMark: Boolean,
    onRemoveMark: (Boolean) -> Unit,
    scrubDetail: Boolean,
    onScrubDetail: (Boolean) -> Unit,
    busy: Boolean,
    onClean: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Accent.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "Take it out?",
                color = Accent,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Only if you want to. The picture keeps its size, its format and its metadata, " +
                    "and the change is far below what an eye can see. Your original is not touched " +
                    "until you save.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            SwitchRow(
                "Remove the hidden mark",
                "Erases the message carried in the colours",
                removeMark,
                onRemoveMark,
            )
            SwitchRow(
                "Clear the finest detail",
                "Overwrites the last bit of every pixel, where data can be tucked away",
                scrubDetail,
                onScrubDetail,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onClean,
                enabled = !busy && (removeMark || scrubDetail),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) { Text("Remove and check", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(body, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Accent),
        )
    }
}

@Composable
private fun CleanedCard(report: WatermarkRemover.Report) {
    val ok = !report.stillPresent && report.markAfter == null
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = (if (ok) Good else Warn).copy(alpha = 0.12f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (ok) "The mark is gone" else "Something is still readable",
                color = if (ok) Good else Warn,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(8.dp))
            report.markBefore?.let {
                Text("was carrying: $it", color = Color.White, fontSize = 13.sp)
            }
            Text(
                if (ok) "checked again afterwards and nothing readable came back"
                else "still reads: " + (report.markAfter ?: "an unnamed pattern"),
                color = Muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "picture changed by %.2f of one colour step on average".format(report.visualDifference),
                color = Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Writes the cleaned pixels back into the working copy and puts the metadata
 * back on afterwards, so removing a mark never silently costs the user their
 * camera details.
 */
private fun saveCleanedInto(
    context: android.content.Context,
    target: java.io.File,
    cleaned: Bitmap,
) {
    val keepMetadata = java.io.File(target.parentFile, target.name + ".meta")
    target.copyTo(keepMetadata, overwrite = true)

    val isPng = target.extension.lowercase() == "png"
    java.io.FileOutputStream(target).use { out ->
        if (isPng) {
            cleaned.compress(Bitmap.CompressFormat.PNG, 100, out)
        } else {
            cleaned.compress(Bitmap.CompressFormat.JPEG, 100, out)
        }
    }

    com.metaforge.ui.Engine.exifTool(context)?.execute(
        "-tagsFromFile", keepMetadata.absolutePath,
        "-all:all", "-unsafe", "-icc_profile", "-m", "-overwrite_original",
        target.absolutePath,
    )
    keepMetadata.delete()
}

@Composable
private fun OnlineCard(
    settings: OnlineCheck.Settings,
    outcome: OnlineCheck.Outcome?,
    busy: Boolean,
    onConfigure: () -> Unit,
    onRun: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("A second opinion", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                if (settings.configured) {
                    "Sends this one picture to your own account at ${settings.provider.label} " +
                        "and brings back their score. Nothing leaves the phone unless you tap it."
                } else {
                    "You can add your own detection account and compare its answer with what was " +
                        "measured here. Nothing is sent anywhere until you set that up and ask for it."
                },
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            when (val o = outcome) {
                is OnlineCheck.Outcome.Scored -> {
                    Text(
                        "${o.provider}: ${o.score} out of 100",
                        color = if (o.score > 60) Warn else Good,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Text(o.detail, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(10.dp))
                }
                is OnlineCheck.Outcome.Failed -> {
                    Text(o.reason, color = Bad, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                }
                null -> Unit
            }
            Row {
                Button(
                    onClick = onRun,
                    enabled = settings.configured && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).height(46.dp),
                ) { Text(if (busy) "Asking" else "Ask", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = onConfigure,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(46.dp),
                ) { Text(if (settings.configured) "Change" else "Set up", color = Accent) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSettingsSheet(
    initial: OnlineCheck.Settings,
    onDismiss: () -> Unit,
    onSave: (OnlineCheck.Settings) -> Unit,
) {
    var provider by remember { mutableStateOf(initial.provider) }
    var user by remember { mutableStateOf(initial.user) }
    var secret by remember { mutableStateOf(initial.secret) }
    var endpoint by remember { mutableStateOf(initial.endpoint) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        Column(
            Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Your detection account", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "The account is yours and the keys stay on this phone. Pictures go straight from " +
                    "here to the service you choose.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row {
                OnlineCheck.Provider.entries.forEach { p ->
                    FilterChip(
                        selected = p == provider,
                        onClick = { provider = p },
                        label = { Text(p.label, fontSize = 12.sp) },
                        modifier = Modifier.padding(end = 6.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent.copy(alpha = 0.22f),
                            selectedLabelColor = Accent,
                            labelColor = Muted,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (provider == OnlineCheck.Provider.CUSTOM) {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("Address to post the picture to") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(10.dp))
            }
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text(if (provider == OnlineCheck.Provider.SIGHTENGINE) "API user" else "User (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text(if (provider == OnlineCheck.Provider.SIGHTENGINE) "API secret" else "Secret (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSave(OnlineCheck.Settings(provider, user.trim(), secret.trim(), endpoint.trim())) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Save", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private class Loaded(val bitmap: Bitmap?, val fullSize: Boolean, val note: String?)

/**
 * Opens a picture without risking the app on a huge one.
 *
 * Anything up to about forty megapixels is read whole. Beyond that the picture
 * is opened smaller so the app stays responsive, and the search for a hidden
 * mark switches to reading the file at full size in pieces instead, because a
 * shrunk picture no longer carries one.
 */
/** 16 MP of ARGB_8888 is 64 MB: large enough to look at, small enough to survive. */
private const val MAX_IN_MEMORY_PIXELS = 16_000_000L

private fun decodeWithinBudget(path: String): Loaded {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
    if (pixels <= 0) return Loaded(null, false, "this file is not a picture")

    // 40 megapixels of ARGB_8888 is a 160 MB allocation. largeHeap or not,
    // that is an out-of-memory kill on most mid-range phones, and it bought
    // nothing: when the picture is downsampled the watermark search already
    // falls back to reading the file at full size in tiles.
    var sample = 1
    while (pixels / (sample.toLong() * sample.toLong()) > MAX_IN_MEMORY_PIXELS) sample *= 2

    val options = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = runCatching { android.graphics.BitmapFactory.decodeFile(path, options) }.getOrNull()
    val note = if (sample > 1) {
        "this picture is ${pixels / 1_000_000} megapixels, so the preview is smaller while the " +
            "full picture is still searched"
    } else {
        null
    }
    return Loaded(bitmap, sample == 1, note)
}
