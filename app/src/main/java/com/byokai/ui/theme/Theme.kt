package com.byokai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = ByokaiBlue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = ByokaiAccent,
    surface = ByokaiSurfaceLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = ByokaiAccent,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF0A0F1C),
    primaryContainer = ByokaiBlueDark,
    surface = ByokaiSurfaceDark,
)

/**
 * BYOKAI Material 3 theme with Dynamic Color support on Android 12+ and
 * light/dark schemes everywhere else.
 */
@Composable
fun ByokaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
