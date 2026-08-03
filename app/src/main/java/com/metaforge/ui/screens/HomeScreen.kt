package com.metaforge.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The first thing the app shows: what the engine is doing, and the four things
 * the app can do to a file. Everything else in MetaForge hangs off here.
 */
@Composable
fun HomeScreen(
    engineStatus: String,
    engineReady: Boolean,
    onInspect: () -> Unit,
    onTransplant: () -> Unit,
    onStrip: () -> Unit,
    onDetect: () -> Unit,
    onProfiles: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    MetaForgeBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(28.dp))

            val pulse = rememberInfiniteTransition(label = "pulse")
            val scale by pulse.animateFloat(
                initialValue = 0.99f,
                targetValue = 1.01f,
                animationSpec = infiniteRepeatable(
                    tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse,
                ),
                label = "scale",
            )
            Text(
                "MetaForge",
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                color = Accent,
                modifier = Modifier.scale(scale),
            )
            Text(
                "everything inside your photos and videos",
                fontSize = 13.sp,
                color = Muted,
            )

            Spacer(Modifier.height(18.dp))
            EngineChip(engineStatus, engineReady, onDiagnostics)
            Spacer(Modifier.height(22.dp))

            ActionCard(
                icon = Icons.Rounded.Info,
                title = "Inspect and edit",
                body = "Every tag in the file: EXIF, IPTC, XMP, ICC, maker notes, QuickTime. " +
                    "Change one, delete one, or write it back.",
                enabled = engineReady,
                onClick = onInspect,
            )
            Spacer(Modifier.height(12.dp))
            ActionCard(
                icon = Icons.Rounded.SwapHoriz,
                title = "Transplant metadata",
                body = "Move the whole metadata identity of one file onto another, then verify " +
                    "tag by tag that nothing was lost.",
                enabled = engineReady,
                onClick = onTransplant,
            )
            Spacer(Modifier.height(12.dp))
            ActionCard(
                icon = Icons.Rounded.Shield,
                title = "Remove everything",
                body = "Strip EXIF, GPS, XMP, maker notes and C2PA blocks before you share, " +
                    "and confirm what is actually left.",
                enabled = engineReady,
                onClick = onStrip,
            )
            Spacer(Modifier.height(12.dp))
            ActionCard(
                icon = Icons.Rounded.Visibility,
                title = "Detect AI images",
                body = "Provenance first, pixels second. It tells you which one the answer " +
                    "rests on instead of showing a fake percentage.",
                enabled = engineReady,
                onClick = onDetect,
            )

            Spacer(Modifier.height(12.dp))
            ActionCard(
                icon = Icons.Rounded.Bookmarks,
                title = "Saved profiles",
                body = "Keep an identity you have already worked out and put it on the next file " +
                    "in one tap, reviewing every tag before it is written.",
                enabled = engineReady,
                onClick = onProfiles,
            )

            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onDiagnostics) {
                Text("Engine", color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun EngineChip(status: String, ready: Boolean, onClick: () -> Unit) {
    val dot = if (ready) Good else Warn
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                status,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    body: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = { if (enabled) onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = if (enabled) 0.06f else 0.03f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) Accent else Muted,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(26.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = if (enabled) Color.White else Muted,
                )
                Spacer(Modifier.height(5.dp))
                Text(body, fontSize = 13.sp, color = Muted, lineHeight = 18.sp)
            }
        }
    }
}
