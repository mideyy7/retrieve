package com.mideyy7.retrieve.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mideyy7.retrieve.R

/*
 * The look of the Retrieve landing page: a warm off-white page, white cards, near-black ink, the
 * app's indigo, and a highlighter yellow for matched words. Light only, like the site.
 */
object Palette {
    val Page = Color(0xFFF7F7F5)
    val Surface = Color(0xFFFFFFFF)
    val Ink = Color(0xFF0F1115)
    val Muted = Color(0xFF4A4B57)
    val Faint = Color(0xFF8A8B98)
    val Line = Color(0xFFE4E3EA)
    val Field = Color(0xFFEFEDF6)
    val Brand = Color(0xFF3A47D5)
    val BrandDeep = Color(0xFF2A33B0)
    val BrandSoft = Color(0xFFE0E0FF)
    val Mark = Color(0xFFFFE24D)
    val Error = Color(0xFFB42318)
}

private val Scheme = lightColorScheme(
    primary = Palette.Brand,
    onPrimary = Color.White,
    primaryContainer = Palette.BrandSoft,
    onPrimaryContainer = Palette.BrandDeep,
    secondary = Palette.Muted,
    onSecondary = Color.White,
    secondaryContainer = Palette.Field,
    onSecondaryContainer = Palette.Ink,
    tertiary = Color(0xFFB4610A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCEBD6),
    onTertiaryContainer = Color(0xFF5A2F00),
    background = Palette.Page,
    onBackground = Palette.Ink,
    surface = Palette.Page,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Field,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerLowest = Palette.Surface,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Color(0xFFF1F0F6),
    surfaceContainerHigh = Palette.Field,
    surfaceContainerHighest = Palette.Line,
    surfaceTint = Color.Transparent,
    outline = Palette.Faint,
    outlineVariant = Palette.Line,
    error = Palette.Error,
    onError = Color.White,
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF7A271A),
)

/** Bundled variable fonts (SIL OFL, licences in assets/licenses), one instance per weight used. */
private fun variable(res: Int, vararg weights: FontWeight) = FontFamily(
    weights.map { w -> Font(res, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight))) },
)

/** Headings: Bricolage Grotesque, set tight. */
val Display = variable(R.font.bricolage_grotesque, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)

/** Body and labels: Instrument Sans. */
val Body = variable(R.font.instrument_sans, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

/** Codes, queries and timestamps: JetBrains Mono. */
val Mono = variable(R.font.jetbrains_mono, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)

private fun display(size: Int, weight: FontWeight, tracking: Double, line: Double = 1.1) =
    TextStyle(fontFamily = Display, fontWeight = weight, fontSize = size.sp, lineHeight = (size * line).sp, letterSpacing = tracking.em)

private fun body(size: Int, weight: FontWeight = FontWeight.Normal, line: Double = 1.5, tracking: Double = 0.0) =
    TextStyle(fontFamily = Body, fontWeight = weight, fontSize = size.sp, lineHeight = (size * line).sp, letterSpacing = tracking.em)

private val AppTypography = Typography(
    displayLarge = display(52, FontWeight.Bold, -0.055, 1.0),
    displayMedium = display(44, FontWeight.Bold, -0.05, 1.0),
    displaySmall = display(36, FontWeight.Bold, -0.045, 1.05),
    headlineLarge = display(32, FontWeight.Bold, -0.04),
    headlineMedium = display(28, FontWeight.Bold, -0.035),
    headlineSmall = display(24, FontWeight.Bold, -0.03),
    titleLarge = display(21, FontWeight.Bold, -0.025, 1.2),
    titleMedium = display(17, FontWeight.SemiBold, -0.02, 1.25),
    titleSmall = body(14, FontWeight.SemiBold, 1.35),
    bodyLarge = body(16),
    bodyMedium = body(14),
    bodySmall = body(13, line = 1.45),
    labelLarge = body(14, FontWeight.SemiBold, 1.3),
    labelMedium = body(12, FontWeight.SemiBold, 1.3),
    labelSmall = body(11, FontWeight.Medium, 1.3, 0.01),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Colours outside Material's scheme: the highlighter behind matched words. */
@Immutable
data class ExtraColors(val highlight: Color, val onHighlight: Color, val dark: Boolean)

private val LocalExtraColors = staticCompositionLocalOf { ExtraColors(Palette.Mark, Palette.Ink, false) }

object RetrieveColors {
    val extra: ExtraColors
        @Composable @ReadOnlyComposable get() = LocalExtraColors.current
}

@Composable
fun RetrieveTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtraColors provides ExtraColors(Palette.Mark, Palette.Ink, dark = false)) {
        MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
