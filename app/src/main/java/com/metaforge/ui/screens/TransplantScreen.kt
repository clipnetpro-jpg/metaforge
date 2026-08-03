package com.metaforge.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaforge.core.OperationProgress
import com.metaforge.data.MediaAccess
import com.metaforge.engine.TransplantEngine
import com.metaforge.ui.Engine
import com.metaforge.ui.components.LiveProgressPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Move one file's metadata identity onto another and prove it arrived.
 *
 * The target is only written back to the gallery when the user asks for it, so
 * a transplant can be inspected, and rejected, before it touches anything.
 */
@Composable
fun TransplantScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = remember { Engine.media(context) }

    var source by remember { mutableStateOf<MediaAccess.Staged?>(null) }
    var target by remember { mutableStateOf<MediaAccess.Staged?>(null) }
    var everything by remember { mutableStateOf(true) }
    var copyBlocks by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<OperationProgress?>(null) }
    var report by remember { mutableStateOf<TransplantEngine.Report?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun stageInto(uri: Uri, into: (MediaAccess.Staged) -> Unit) {
        busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { media.stage(uri) } }
                .onSuccess { into(it); report = null; progress = null }
                .onFailure { message = it.message ?: "could not open that file" }
            busy = false
        }
    }

    val pickSource = rememberFilePicker(IMAGE_AND_VIDEO) { uri -> stageInto(uri) { source = it } }
    val pickTarget = rememberFilePicker(IMAGE_AND_VIDEO) { uri -> stageInto(uri) { target = it } }

    fun run() {
        val s = source ?: return
        val t = target ?: return
        val et = Engine.exifTool(context)
        if (et == null) {
            message = "engine unavailable: ${Engine.failure()}"
            return
        }
        busy = true
        report = null
        message = null
        scope.launch {
            val engine = TransplantEngine(et)
            runCatching {
                engine.transplant(
                    source = s.workingCopy,
                    target = t.workingCopy,
                    mode = if (everything) TransplantEngine.Mode.EVERYTHING
                           else TransplantEngine.Mode.FILL_GAPS_ONLY,
                    copyRawBlocks = copyBlocks,
                ).flowOn(Dispatchers.IO).collect { progress = it }
            }.onFailure { message = it.message ?: "transplant failed" }
            report = engine.lastReport
            busy = false
        }
    }

    fun save() {
        val t = target ?: return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { media.commit(t) }
            message = if (r.isSuccess) "written back to ${t.displayName}"
                      else "could not save: ${r.exceptionOrNull()?.message}"
            busy = false
        }
    }

    ScreenScaffold(
        title = "Transplant metadata",
        subtitle = "from one file onto another",
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
                label = "Source: metadata is read from here",
                fileName = source?.displayName,
                detail = source?.let { humanSize(it.sizeBytes) },
                enabled = !busy,
                onPick = pickSource,
            )
            Spacer(Modifier.height(10.dp))
            FileSlot(
                label = "Target: metadata is written into here",
                fileName = target?.displayName,
                detail = target?.let { humanSize(it.sizeBytes) },
                enabled = !busy,
                onPick = pickTarget,
            )

            Spacer(Modifier.height(14.dp))
            ToggleRow(
                "Copy everything",
                if (everything) "Every writable tag, overwriting what the target already has"
                else "Only fill in tags the target is missing",
                everything,
            ) { everything = it }
            ToggleRow(
                "Copy raw blocks",
                "C2PA and other blocks ExifTool can read but not write",
                copyBlocks,
            ) { copyBlocks = it }

            Spacer(Modifier.height(16.dp))
            RunButton(
                text = if (busy) "Working..." else "Transplant",
                enabled = !busy && source != null && target != null,
            ) { run() }

            progress?.let {
                Spacer(Modifier.height(16.dp))
                LiveProgressPanel(it)
            }

            report?.let { r ->
                Spacer(Modifier.height(16.dp))
                ReportCard(r)
                Spacer(Modifier.height(12.dp))
                RunButton("Save into ${target?.displayName ?: "target"}", !busy) { save() }
            }

            message?.let {
                Spacer(Modifier.height(12.dp))
                InfoCard("Result", it, if (it.contains("could not")) Bad else Good)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(body, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Accent,
            ),
        )
    }
}

@Composable
private fun ReportCard(report: TransplantEngine.Report) {
    val tint = if (report.complete) Good else Warn
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (report.complete) "Every tag arrived" else "Partly copied",
                color = tint,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${report.copiedCount} of ${report.sourceTagCount} tags verified " +
                    "(${report.coveragePercent}%)",
                color = Color.White,
                fontSize = 14.sp,
            )
            if (report.rawBlocksCopied.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "raw blocks: ${report.rawBlocksCopied.joinToString()}",
                    color = Muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (report.missing.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Could not be written:", color = Warn, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                report.missing.take(12).forEach {
                    Text(
                        it.tag,
                        color = Muted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (report.missing.size > 12) {
                    Text("and ${report.missing.size - 12} more", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}
