package com.example.filmio.core.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val FilmioLightColorScheme = lightColorScheme(
    primary = Color(0xFF006B50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCBF1DF),
    onPrimaryContainer = Color(0xFF004D37),
    secondary = Color(0xFF505D54),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCBF1DF),
    onSecondaryContainer = Color(0xFF004D37),
    background = Color(0xFFF8FAF7),
    onBackground = Color(0xFF18221B),
    surface = Color(0xFFF8FAF7),
    onSurface = Color(0xFF18221B),
    onSurfaceVariant = Color(0xFF505D54),
    surfaceContainer = Color(0xFFEAF0E9),
    surfaceContainerLow = Color(0xFFF0F4EE),
    outlineVariant = Color(0xFFD8E1D8),
    error = Color(0xFF943C32),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF752B25),
)

val FilmioDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8DD8B7),
    onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF214C39),
    onPrimaryContainer = Color(0xFFC8F2DC),
    secondary = Color(0xFFB9C6BA),
    onSecondary = Color(0xFF233028),
    secondaryContainer = Color(0xFF214C39),
    onSecondaryContainer = Color(0xFFC8F2DC),
    background = Color(0xFF101914),
    onBackground = Color(0xFFE3EBE2),
    surface = Color(0xFF101914),
    onSurface = Color(0xFFE3EBE2),
    onSurfaceVariant = Color(0xFFB9C6BA),
    surfaceContainer = Color(0xFF233028),
    surfaceContainerLow = Color(0xFF1A251E),
    outlineVariant = Color(0xFF39483E),
    error = Color(0xFFFFB4A9),
    errorContainer = Color(0xFF542E29),
    onErrorContainer = Color(0xFFFFDAD6),
)

val FilmioTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun FilmioTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) FilmioDarkColorScheme else FilmioLightColorScheme,
        typography = FilmioTypography,
        content = content,
    )
}
