package com.metaforge.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0B0B12)
val Panel = Color(0xFF161427)
val Accent = Color(0xFF22D3EE)
val Warn = Color(0xFFF59E0B)
val Bad = Color(0xFFEF4444)
val Good = Color(0xFF34D399)
val Muted = Color(0xFF8B8BA7)

/** The dark gradient every screen sits on. */
@Composable
fun MetaForgeBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ink, Panel, Ink))),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    MetaForgeBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                    ),
                    title = {
                        Column {
                            Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            if (subtitle != null) {
                                Text(subtitle, fontSize = 12.sp, color = Muted)
                            }
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                )
                            }
                        }
                    },
                    actions = actions,
                )
            },
            bottomBar = bottomBar,
            content = content,
        )
    }
}

/** Remembers a document picker limited to the given mime types. */
@Composable
fun rememberFilePicker(mimeTypes: Array<String>, onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onPicked(uri) }
    return { launcher.launch(mimeTypes) }
}

val IMAGE_AND_VIDEO = arrayOf("image/*", "video/*")
val IMAGE_ONLY = arrayOf("image/*")

/** The "choose a file" slot used at the top of every operation screen. */
@Composable
fun FileSlot(
    label: String,
    fileName: String?,
    detail: String? = null,
    enabled: Boolean = true,
    onPick: () -> Unit,
) {
    Card(
        onClick = { if (enabled) onPick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(4.dp))
                Text(
                    fileName ?: "Tap to choose",
                    color = if (fileName == null) Muted else Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                if (detail != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(detail, fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
                }
            }
            Icon(Icons.Rounded.Add, contentDescription = null, tint = Accent)
        }
    }
}

@Composable
fun InfoCard(title: String, body: String, tint: Color = Muted) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = tint)
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f), lineHeight = 18.sp)
        }
    }
}

/** Big primary action at the bottom of an operation screen. */
@Composable
fun RunButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

fun humanSize(bytes: Long): String = when {
    bytes < 0 -> "unknown size"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
