package com.metaforge.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metaforge.ai.Hotspot

/**
 * Draws the detector's findings directly on the photo: a suspicion heatmap and
 * labelled boxes around the regions that drove the verdict.
 *
 * The boxes are not decoration. Each one corresponds to a measured hotspot, and
 * its label is the reason the engine recorded for that region, so a user can see
 * *where* the app is looking and judge whether it is being sensible.
 */
@Composable
fun EvidenceOverlay(
    hotspots: List<Hotspot>,
    heatmap: ImageBitmap?,
    modifier: Modifier = Modifier,
    showHeatmap: Boolean = true,
    showBoxes: Boolean = true,
    heatmapOpacity: Float = 0.55f,
) {
    val measurer = rememberTextMeasurer()

    // Boxes draw themselves on with a short reveal so the eye follows them.
    val reveal = remember(hotspots) { Animatable(0f) }
    LaunchedEffect(hotspots) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
    }
    val breathe by rememberInfiniteTransition(label = "box").animateFloat(
        0.55f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    Canvas(modifier.fillMaxSize()) {
        if (showHeatmap && heatmap != null) {
            drawImage(
                image = heatmap,
                dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                alpha = heatmapOpacity,
                blendMode = BlendMode.Screen,
            )
        }
        if (!showBoxes) return@Canvas

        hotspots.take(6).forEachIndexed { index, spot ->
            val appear = ((reveal.value * hotspots.size) - index).coerceIn(0f, 1f)
            if (appear <= 0f) return@forEachIndexed
            drawHotspot(spot, appear, breathe, measurer)
        }
    }
}

private fun DrawScope.drawHotspot(
    spot: Hotspot,
    appear: Float,
    breathe: Float,
    measurer: TextMeasurer,
) {
    val r = Rect(
        left = spot.bounds.left * size.width,
        top = spot.bounds.top * size.height,
        right = spot.bounds.right * size.width,
        bottom = spot.bounds.bottom * size.height,
    )

    // Colour by severity: amber for mild, red for strong.
    val hue = lerp(48f, 4f, spot.score.coerceIn(0f, 1f))
    val colour = Color.hsv(hue, 0.85f, 1f)
    val alpha = appear * (0.7f + 0.3f * breathe)

    drawRect(
        color = colour.copy(alpha = 0.10f * appear),
        topLeft = Offset(r.left, r.top),
        size = Size(r.width, r.height),
    )

    // Corner brackets rather than a full box, so the photo stays readable.
    val arm = minOf(r.width, r.height) * 0.26f
    val stroke = 3f
    listOf(
        Offset(r.left, r.top) to listOf(Offset(arm, 0f), Offset(0f, arm)),
        Offset(r.right, r.top) to listOf(Offset(-arm, 0f), Offset(0f, arm)),
        Offset(r.left, r.bottom) to listOf(Offset(arm, 0f), Offset(0f, -arm)),
        Offset(r.right, r.bottom) to listOf(Offset(-arm, 0f), Offset(0f, -arm)),
    ).forEach { (corner, arms) ->
        arms.forEach { a ->
            drawLine(
                color = colour.copy(alpha = alpha),
                start = corner,
                end = Offset(corner.x + a.x, corner.y + a.y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }

    drawRect(
        color = colour.copy(alpha = 0.35f * appear),
        topLeft = Offset(r.left, r.top),
        size = Size(r.width, r.height),
        style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 9f))),
    )

    // Label: what was found here and how strongly.
    val text = "${spot.reason}  ${(spot.score * 100).toInt()}%"
    val layout = measurer.measure(
        text,
        style = TextStyle(fontSize = 11.sp, color = Color.Black),
    )
    val padX = 8f
    val padY = 4f
    val boxW = layout.size.width + padX * 2
    val boxH = layout.size.height + padY * 2
    val lx = r.left.coerceAtMost(size.width - boxW)
    val ly = (r.top - boxH - 6f).coerceAtLeast(0f)

    drawRoundRect(
        color = colour.copy(alpha = 0.92f * appear),
        topLeft = Offset(lx, ly),
        size = Size(boxW, boxH),
        cornerRadius = CornerRadius(6f, 6f),
    )
    translate(lx + padX, ly + padY) {
        drawText(layout)
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
