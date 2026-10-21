package com.rescuemesh.ai.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AmberPrimary,
    onPrimary = BackgroundDark,
    primaryContainer = AmberContainer,
    onPrimaryContainer = AmberLight,

    secondary = BlueInfo,
    onSecondary = BackgroundDark,
    secondaryContainer = BlueContainer,
    onSecondaryContainer = BlueLight,

    tertiary = GreenPassable,
    onTertiary = BackgroundDark,
    tertiaryContainer = GreenContainer,
    onTertiaryContainer = GreenLight,

    error = RedBlocked,
    onError = BackgroundDark,
    errorContainer = RedContainer,
    onErrorContainer = RedLight,

    background = BackgroundDark,
    onBackground = TextPrimary,

    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,

    outline = SurfaceBorder,
    outlineVariant = SurfaceVariant
)

@Composable
fun RescueMeshTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = BackgroundDark.toArgb()
                window.navigationBarColor = BackgroundDark.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
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
    content: @Composable () -> Unit
) = RescueMeshTheme(content = content)
