package com.metaforge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Indigo = Color(0xFF6C5CE7)
private val Cyan = Color(0xFF22D3EE)
private val Ink = Color(0xFF0B0B12)
private val Surface = Color(0xFF14141F)

private val DarkColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Ink,
    secondary = Indigo,
    background = Ink,
    surface = Surface,
    surfaceVariant = Color(0xFF1D1D2B),
)

private val LightColors = lightColorScheme(
    primary = Indigo,
    secondary = Cyan,
)

@Composable
fun MetaForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
