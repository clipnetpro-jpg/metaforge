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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaforge.ui.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A plain answer to "is it working, and how fast".
 *
 * Deliberately says nothing about how the app is built. What the engine is made
 * of is our business; what the user needs is whether it is ready, how quickly it
 * answers, and what it can open.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf<Boolean?>(null) }
    var roundTripMs by remember { mutableStateOf(0.0) }
    var formats by remember { mutableStateOf(0) }
    var formatList by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var storageBytes by remember { mutableStateOf(0L) }

    val media = remember { Engine.media(context) }

    fun refresh() {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                storageBytes = media.storageUsedBytes()
                val engine = Engine.exifTool(context)
                if (engine == null) {
                    ready = false
                    return@withContext
                }
                val t0 = System.nanoTime()
                engine.execute("-ver")
                roundTripMs = (System.nanoTime() - t0) / 1_000_000.0
                val list = engine.execute("-listf").stdout
                    .substringAfter(":")
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                formats = list.size
                formatList = list.joinToString(" ")
                ready = true
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    ScreenScaffold(title = "Engine", subtitle = "speed and supported files", onBack = onBack) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            WorkingBar(busy, "Checking")

            Spacer(Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = (if (ready == false) Bad else Good).copy(alpha = 0.10f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        when (ready) {
                            true -> "Ready"
                            false -> "Not available on this device"
                            null -> "Starting"
                        },
                        color = if (ready == false) Bad else Good,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                    if (ready == true) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "%.0f ms per request".format(roundTripMs),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "$formats file formats can be opened",
                            color = Muted,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            // The user is entitled to know how much of their phone this is
            // using and to get it back in one tap, rather than discovering it
            // in Android's app settings.
            Spacer(Modifier.height(14.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Space in use", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        humanSize(storageBytes),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Text(
                        "Working copies of files you opened, plus undo copies of files " +
                            "you saved over. Working copies clear themselves after six " +
                            "hours, undo copies after seven days.",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { media.clearAllStorage() }
                                storageBytes = withContext(Dispatchers.IO) { media.storageUsedBytes() }
                            }
                        },
                        enabled = !busy && storageBytes > 0,
                    ) {
                        Text("Clear it now", color = Accent)
                    }
                    Text(
                        "Clearing also discards every undo copy, so anything you saved " +
                            "over can no longer be put back.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }

            if (formatList.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                InfoCard("What you can open", formatList)
            }

            Spacer(Modifier.height(16.dp))
            RunButton("Check again", !busy) { refresh() }
            Spacer(Modifier.height(28.dp))
        }
    }
}
