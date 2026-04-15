package com.example.raspberrycontroller.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary            = Teal80,
    onPrimary          = Color(0xFF00201E),
    primaryContainer   = Color(0xFF004F49),
    onPrimaryContainer = Color(0xFF9EF2E8),
    secondary          = TealGrey80,
    onSecondary        = Color(0xFF00201D),
    secondaryContainer = Color(0xFF1E4A46),
    onSecondaryContainer= Color(0xFFB2DFDB),
    tertiary           = Cyan80,
    onTertiary         = Color(0xFF00363D),
    background         = DarkBackground,
    onBackground       = Color(0xFFDCE8F0),
    surface            = DarkSurface,
    onSurface          = Color(0xFFDCE8F0),
    surfaceVariant     = DarkSurfaceVar,
    onSurfaceVariant   = Color(0xFFB0C4D8),
    outline            = Color(0xFF4A6077)
)

private val LightColorScheme = lightColorScheme(
    primary            = Teal40,
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFF9EF2E8),
    onPrimaryContainer = Color(0xFF00201E),
    secondary          = TealGrey40,
    onSecondary        = Color(0xFFFFFFFF),
    secondaryContainer = LightSurfaceVar,
    onSecondaryContainer= Color(0xFF00201D),
    tertiary           = Cyan40,
    onTertiary         = Color(0xFFFFFFFF),
    background         = LightBackground,
    onBackground       = Color(0xFF161D1E),
    surface            = LightSurface,
    onSurface          = Color(0xFF161D1E),
    surfaceVariant     = LightSurfaceVar,
    onSurfaceVariant   = Color(0xFF3D5957),
    outline            = Color(0xFF6F7979)
)

@Composable
fun RaspberryControllerTheme(
    darkTheme   : Boolean = isSystemInDarkTheme(),
    // ✅ FIX : false pour que tes couleurs custom soient utilisées
    // Si true sur Android 12+, le système ignore DarkColorScheme/LightColorScheme
    // et applique les couleurs du fond d'écran (Monet) → le thème ne changeait pas
    dynamicColor: Boolean = false,
    content     : @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}