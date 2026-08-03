package com.metaforge.ui.screens

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
import com.metaforge.engine.PrivacyStripper
import com.metaforge.ui.Engine
import com.metaforge.ui.components.LiveProgressPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Erase everything a file says about you, then show what is genuinely left.
 *
 * The verification pass is the point. Plenty of tools claim to strip metadata
 * and leave a C2PA block or a maker note behind; this one re-reads the file and
 * names anything that survived.
 */
@Composable
fun StripScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = remember { Engine.media(context) }

    var staged by remember { mutableStateOf<MediaAccess.Staged?>(null) }
    var keepOrientation by remember { mutableStateOf(true) }
    var keepColour by remember { mutableStateOf(true) }
    var keepDate by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<OperationProgress?>(null) }
    var report by remember { mutableStateOf<PrivacyStripper.Report?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val pick = rememberFilePicker(IMAGE_AND_VIDEO) { uri ->
        busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { media.stage(uri) } }
                .onSuccess { staged = it; report = null; progress = null; message = null }
                .onFailure { message = it.message ?: "could not open that file" }
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
        report = null
        scope.launch {
            val stripper = PrivacyStripper(et)
            runCatching {
                stripper.strip(
                    s.workingCopy,
                    PrivacyStripper.Options(keepOrientation, keepColour, keepDate),
                ).flowOn(Dispatchers.IO).collect { progress = it }
            }.onFailure { message = it.message ?: "strip failed" }
            report = stripper.lastReport
            busy = false
        }
    }

    fun save() {
        val s = staged ?: return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { media.commit(s) }
            message = if (r.isSuccess) "clean file written back over ${s.displayName}"
                      else "could not save: ${r.exceptionOrNull()?.message}"
            busy = false
        }
    }

    ScreenScaffold(
        title = "Remove metadata",
        subtitle = staged?.displayName ?: "nothing chosen",
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
                label = "File to clean",
                fileName = staged?.displayName,
                detail = staged?.let { humanSize(it.sizeBytes) },
                enabled = !busy,
                onPick = pick,
            )

            Spacer(Modifier.height(14.dp))
            KeepRow("Keep orientation", "so the photo does not display sideways", keepOrientation) { keepOrientation = it }
            KeepRow("Keep colour profile", "so colours do not shift", keepColour) { keepColour = it }
            KeepRow("Keep capture date", "drop everything else, but remember when it was taken", keepDate) { keepDate = it }

            Spacer(Modifier.height(16.dp))
            RunButton(if (busy) "Working..." else "Remove everything", !busy && staged != null) { run() }

            progress?.let {
                Spacer(Modifier.height(16.dp))
                LiveProgressPanel(it)
            }

            report?.let { r ->
                Spacer(Modifier.height(16.dp))
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = (if (r.clean) Good else Warn).copy(alpha = 0.10f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            if (r.clean) "Nothing identifying left" else "Something survived",
                            color = if (r.clean) Good else Warn,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${r.removedTagCount} tags removed, ${humanSize(r.bytesSaved)} smaller",
                            color = Color.White,
                            fontSize = 14.sp,
                        )
                        if (r.rawBlocksRemoved.isNotEmpty()) {
                            Text(
                                "blocks: ${r.rawBlocksRemoved.joinToString()}",
                                color = Muted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (r.keptDeliberately.isNotEmpty()) {
                            Text(
                                "kept on purpose: ${r.keptDeliberately.joinToString()}",
                                color = Muted,
                                fontSize = 12.sp,
                            )
                        }
                        if (r.remaining.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Still present:", color = Warn, fontSize = 13.sp)
                            r.remaining.take(10).forEach {
                                Text(it, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                RunButton("Save the cleaned file", !busy) { save() }
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
private fun KeepRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(body, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Accent),
        )
    }
}
