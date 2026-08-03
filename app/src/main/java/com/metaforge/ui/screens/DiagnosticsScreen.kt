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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaforge.engine.PerlRuntime
import com.metaforge.ui.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the engine knows about itself, in one screen.
 *
 * When something does go wrong on a device we have never seen, this is the
 * screen that answers it: the real Perl error, verbatim, rather than a shrug.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("running self test...") }
    var busy by remember { mutableStateOf(true) }

    fun refresh() {
        busy = true
        scope.launch {
            text = withContext(Dispatchers.IO) { selfTest(context) }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    ScreenScaffold(
        title = "Engine diagnostics",
        subtitle = "Perl, ExifTool and the runtime tree",
        onBack = onBack,
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (busy) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = Accent,
                    trackColor = Color.White.copy(alpha = 0.08f),
                )
                Spacer(Modifier.height(12.dp))
            }
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text,
                    modifier = Modifier.padding(16.dp),
                    color = Color(0xFFB9F5FF),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.height(14.dp))
            RunButton("Run again", !busy) { refresh() }
            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun selfTest(context: android.content.Context): String = buildString {
    val ok = PerlRuntime.ensureReady(context)
    appendLine("runtime ready : $ok")
    appendLine(PerlRuntime.describe())
    if (!ok) return@buildString
    appendLine("perl          : " + PerlRuntime.runOnce("-e", "print \"\$^V on \$^O\""))
    appendLine("modules       : " + PerlRuntime.runOnce(
        "-MPOSIX", "-MFcntl", "-MIO::File", "-MEncode", "-e", "print 'POSIX Fcntl IO Encode loaded'",
    ))
    val et = Engine.exifTool(context)
    if (et == null) {
        appendLine()
        appendLine("ExifTool      : FAILED TO START")
        appendLine(Engine.failure())
        return@buildString
    }
    appendLine("ExifTool      : ${et.version()} in ${et.mode} mode")
    val t0 = System.nanoTime()
    et.execute("-ver")
    appendLine("round trip    : %.1f ms".format((System.nanoTime() - t0) / 1_000_000.0))
    appendLine()
    appendLine("supported formats:")
    appendLine(et.execute("-listf").stdout.take(700))
}
