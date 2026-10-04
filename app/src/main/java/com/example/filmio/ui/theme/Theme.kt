package com.example.filmio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.filmio.core.presentation.theme.FilmioDarkColorScheme
import com.example.filmio.core.presentation.theme.FilmioLightColorScheme
import com.example.filmio.core.presentation.theme.FilmioTypography

@Composable
fun FilmioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FilmioDarkColorScheme
        else -> FilmioLightColorScheme
    }
    MaterialTheme(colorScheme = colorScheme, typography = FilmioTypography, content = content)
}
