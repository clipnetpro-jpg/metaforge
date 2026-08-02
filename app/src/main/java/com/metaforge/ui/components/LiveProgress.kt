package com.metaforge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaforge.core.OperationProgress
import com.metaforge.core.StageProgress
import com.metaforge.core.StageState

/**
 * The live status panel every operation in MetaForge reports through.
 *
 * It shows the named stages, which one is running, a real percentage and the
 * measured duration of each finished stage. No indeterminate spinners and no
 * invented progress: every number here comes from the engine.
 */
@Composable
fun LiveProgressPanel(
    progress: OperationProgress,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    progress.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(progress.overallFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(10.dp))
            val animated by animateFloatAsState(
                targetValue = progress.overallFraction,
                animationSpec = tween(280, easing = FastOutSlowInEasing),
                label = "overall",
            )
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.surface,
            )

            Spacer(Modifier.height(16.dp))
            progress.stages.forEachIndexed { i, stage ->
                StageRow(index = i + 1, total = progress.stages.size, stage = stage)
                if (i != progress.stages.lastIndex) Spacer(Modifier.height(10.dp))
            }

            progress.error?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StageRow(index: Int, total: Int, stage: StageProgress) {
    val done = stage.state == StageState.DONE
    val running = stage.state == StageState.RUNNING
    val failed = stage.state == StageState.FAILED
    val skipped = stage.state == StageState.SKIPPED

    val tint by animateColorAsState(
        when {
            failed -> MaterialTheme.colorScheme.error
            done -> MaterialTheme.colorScheme.primary
            running -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        },
        label = "tint",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                done -> Icon(Icons.Default.Check, null, tint = tint, modifier = Modifier.size(14.dp))
                failed -> Icon(Icons.Default.Close, null, tint = tint, modifier = Modifier.size(14.dp))
                skipped -> Icon(Icons.Default.Remove, null, tint = tint, modifier = Modifier.size(14.dp))
                running -> {
                    val spin = rememberInfiniteTransition(label = "spin")
                    val angle by spin.animateFloat(
                        0f, 360f,
                        infiniteRepeatable(tween(900, easing = LinearEasing)),
                        label = "angle",
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp).rotate(angle),
                        strokeWidth = 2.dp,
                        color = tint,
                    )
                }
                else -> Text(
                    "$index",
                    fontSize = 10.sp,
                    color = tint,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$index/$total  ${stage.stage.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (running) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (done || running || failed) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                stage.elapsedMs?.let {
                    Text(
                        if (it < 1000) "$it ms" else "%.1f s".format(it / 1000.0),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            stage.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running && (stage.fraction ?: 0f) > 0f) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { stage.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    trackColor = Color.Transparent,
                )
            }
        }
    }
}
