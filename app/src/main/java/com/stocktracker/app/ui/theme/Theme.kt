package com.stocktracker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Gain = Color(0xFF26C281)
val Loss = Color(0xFFE74C3C)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4CC9F0),
    onPrimary = Color(0xFF00344A),
    secondary = Color(0xFF80FFDB),
    background = Color(0xFF0D1B2A),
    onBackground = Color(0xFFE0E6ED),
    surface = Color(0xFF16283C),
    onSurface = Color(0xFFE0E6ED),
    surfaceVariant = Color(0xFF1E344D),
    onSurfaceVariant = Color(0xFF9DB2C8),
    error = Loss
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0077B6),
    secondary = Color(0xFF00B4D8),
    background = Color(0xFFF4F7FA),
    surface = Color.White,
    error = Loss
)

@Composable
fun StockTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
