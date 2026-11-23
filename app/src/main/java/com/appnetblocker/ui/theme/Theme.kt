package com.appnetblocker.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F4C5C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBCEBF7),
    onPrimaryContainer = Color(0xFF001F28),
    secondary = Color(0xFF3E6370),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC2E8F7),
    onSecondaryContainer = Color(0xFF001F29),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7FAFB),
    onBackground = Color(0xFF171C1E),
    surface = Color(0xFFF7FAFB),
    onSurface = Color(0xFF171C1E),
    surfaceVariant = Color(0xFFDBE4E8),
    onSurfaceVariant = Color(0xFF40484C)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CD1E5),
    onPrimary = Color(0xFF003543),
    primaryContainer = Color(0xFF004E61),
    onPrimaryContainer = Color(0xFFBCEBF7),
    secondary = Color(0xFFA6CCDB),
    onSecondary = Color(0xFF0A3541),
    secondaryContainer = Color(0xFF254B58),
    onSecondaryContainer = Color(0xFFC2E8F7),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF0F1416),
    onBackground = Color(0xFFDFE3E6),
    surface = Color(0xFF0F1416),
    onSurface = Color(0xFFDFE3E6),
    surfaceVariant = Color(0xFF40484C),
    onSurfaceVariant = Color(0xFFC0C8CC)
)

@Composable
fun AppNetBlockerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
