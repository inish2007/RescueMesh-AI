package com.rescuemesh.ai.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = LightPrimaryTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F4F1),
    onPrimaryContainer = Color(0xFF042F2C),

    secondary = LightActionBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFF8FF),
    onSecondaryContainer = Color(0xFF175CD3),

    tertiary = LightWarningAmber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF0C7),
    onTertiaryContainer = Color(0xFF4E3400),

    error = LightEmergencyRed,
    onError = Color.White,
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF7A271A),

    background = LightBackground,
    onBackground = LightTextPrimary,

    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,

    outline = LightBorder,
    outlineVariant = Color(0xFFF2F4F7)
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimaryTeal,
    onPrimary = DarkBackground,
    primaryContainer = Color(0xFF164E46),
    onPrimaryContainer = Color(0xFFB2ECE4),

    secondary = DarkActionBlue,
    onSecondary = DarkBackground,
    secondaryContainer = Color(0xFF1849A9),
    onSecondaryContainer = Color(0xFFD1E9FF),

    tertiary = DarkWarningAmber,
    onTertiary = DarkBackground,
    tertiaryContainer = Color(0xFF7A4E00),
    onTertiaryContainer = Color(0xFFFEDF89),

    error = DarkEmergencyRed,
    onError = DarkBackground,
    errorContainer = Color(0xFF7A271A),
    onErrorContainer = Color(0xFFFEE4E2),

    background = DarkBackground,
    onBackground = DarkTextPrimary,

    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,

    outline = DarkBorder,
    outlineVariant = DarkSurfaceVariant
)

@Composable
fun RescueMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val statusBarBg = if (darkTheme) DarkBackground else LightBackground
                window.statusBarColor = statusBarBg.toArgb()
                window.navigationBarColor = statusBarBg.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun RescueMeshAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) = RescueMeshTheme(darkTheme = darkTheme, content = content)
