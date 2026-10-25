package com.moblized.courseshelf.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF1F6F6B)
private val TealLight = Color(0xFF7FD1C9)
private val Paper = Color(0xFFF7F3EC)
private val Ink = Color(0xFF121417)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBFEDE7),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF8A5A1C),
    background = Paper,
    surface = Paper,
    surfaceContainer = Color(0xFFEFE9DF),
    surfaceContainerHigh = Color(0xFFE9E2D7),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF0D504C),
    onPrimaryContainer = Color(0xFFBFEDE7),
    secondary = Color(0xFFF0BF7A),
    background = Ink,
    surface = Ink,
    surfaceContainer = Color(0xFF1B1E22),
    surfaceContainerHigh = Color(0xFF24282D),
)

@Composable
fun CourseShelfTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
