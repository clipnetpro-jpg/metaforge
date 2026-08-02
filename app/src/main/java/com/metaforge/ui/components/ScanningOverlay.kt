package com.metaforge.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The live scanning effect drawn over a photo while it is being analysed.
 *
 * A sweep line travels down the image, a soft grid pulses behind it, and the
 * already-scanned band stays tinted so the user can see how far the pass has
 * got. The sweep position is driven by [progress], so it tracks the real
 * analysis rather than looping decoratively: when the engine stalls, the line
 * stalls too, which is honest feedback.
 */
@Composable
fun ScanningOverlay(
    active: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF22D3EE),
) {
    if (!active) return

    val transition = rememberInfiniteTransition(label = "scan")
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "shimmer",
    )
    val sweep by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "sweep",
    )

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val y = h * sweep

            // Tint the region already analysed.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to accent.copy(alpha = 0.05f),
                    1f to accent.copy(alpha = 0.16f),
                ),
                size = Size(w, y),
            )

            // Faint measurement grid, breathing.
            val step = h / 14f
            var gy = 0f
            while (gy < h) {
                drawLine(
                    color = accent.copy(alpha = 0.05f + pulse * 0.04f),
                    start = Offset(0f, gy),
                    end = Offset(w, gy),
                    strokeWidth = 1f,
                )
                gy += step
            }
            var gx = 0f
            while (gx < w) {
                drawLine(
                    color = accent.copy(alpha = 0.04f),
                    start = Offset(gx, 0f),
                    end = Offset(gx, h),
                    strokeWidth = 1f,
                )
                gx += step
            }

            // Glow trailing behind the sweep line.
            val trail = h * 0.14f
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to accent.copy(alpha = 0.45f * pulse),
                ),
                topLeft = Offset(0f, (y - trail).coerceAtLeast(0f)),
                size = Size(w, minOf(trail, y)),
            )

            // The sweep line itself.
            drawLine(
                brush = Brush.horizontalGradient(
                    0f to accent.copy(alpha = 0.1f),
                    0.5f to accent,
                    1f to accent.copy(alpha = 0.1f),
                ),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 2.5f,
            )

            // Travelling highlight along the line, so it reads as "working".
            val hx = w * shimmer
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.65f), Color.Transparent),
                    center = Offset(hx, y),
                    radius = 60f,
                ),
                radius = 60f,
                center = Offset(hx, y),
            )

            // Corner brackets, like a viewfinder locking on.
            val len = minOf(w, h) * 0.08f
            val inset = 10f
            val bracket = accent.copy(alpha = 0.55f + pulse * 0.25f)
            listOf(
                Offset(inset, inset) to listOf(Offset(len, 0f), Offset(0f, len)),
                Offset(w - inset, inset) to listOf(Offset(-len, 0f), Offset(0f, len)),
                Offset(inset, h - inset) to listOf(Offset(len, 0f), Offset(0f, -len)),
                Offset(w - inset, h - inset) to listOf(Offset(-len, 0f), Offset(0f, -len)),
            ).forEach { (corner, arms) ->
                arms.forEach { arm ->
                    drawLine(
                        color = bracket,
                        start = corner,
                        end = Offset(corner.x + arm.x, corner.y + arm.y),
                        strokeWidth = 3f,
                    )
                }
            }
        }
    }
}

/** A soft pulsing ring used on idle cards to show a background check is running. */
@Composable
fun PulsingIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF22D3EE),
) {
    val t = rememberInfiniteTransition(label = "ping")
    val r by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "r")
    val a by t.animateFloat(0.6f, 0f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "a")
    Canvas(modifier) {
        val maxR = size.minDimension / 2
        drawCircle(color = color.copy(alpha = a), radius = maxR * r, style = Stroke(width = 2f))
        drawCircle(color = color, radius = maxR * 0.28f)
    }
}
