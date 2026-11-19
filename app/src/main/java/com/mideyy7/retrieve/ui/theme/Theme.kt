package com.mideyy7.retrieve.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand: a deep indigo ("ink") with a highlighter yellow for matched words, the two things a student
// revising from slides actually has on their desk.
private val Indigo = Color(0xFF3A47D5)
private val IndigoLight = Color(0xFFBEC2FF)

private val Light = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E0FF),
    onPrimaryContainer = Color(0xFF00006E),
    secondary = Color(0xFF5B5D72),
    secondaryContainer = Color(0xFFE0E0F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF8A5100),
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF6),
    surfaceContainerHigh = Color(0xFFE9E7F0),
    surfaceContainerHighest = Color(0xFFE3E1EA),
    onSurfaceVariant = Color(0xFF46464F),
    outlineVariant = Color(0xFFC7C5D0),
)

private val Dark = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF0D16A6),
    primaryContainer = Color(0xFF2830BE),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFFC4C4DD),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFFFFB870),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outlineVariant = Color(0xFF46464F),
)

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

/** Colours outside Material's scheme: the highlighter behind matched words. */
@Immutable
data class ExtraColors(val highlight: Color, val onHighlight: Color, val dark: Boolean)

private val LocalExtraColors = staticCompositionLocalOf { ExtraColors(Color.Yellow, Color.Black, false) }

object RetrieveColors {
    val extra: ExtraColors
        @Composable @ReadOnlyComposable get() = LocalExtraColors.current
}

@Composable
fun RetrieveTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val extra = if (dark) ExtraColors(Color(0xFF6B5A00), Color(0xFFFFF4C2), true)
    else ExtraColors(Color(0xFFFFE97A), Color(0xFF221B00), false)
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
