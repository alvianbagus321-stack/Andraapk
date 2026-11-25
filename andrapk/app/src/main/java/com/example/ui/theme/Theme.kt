package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

private val LiquidGlassColorScheme = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = JarvisOnPrimary,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = JarvisTeal,
    onSecondary = SecondaryOnDark,
    secondaryContainer = JarvisSurfaceHighlight,
    onSecondaryContainer = SecondaryContainerOnDark,
    tertiary = JarvisEmerald,
    onTertiary = TertiaryOnDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = DepthObsidian,
    onBackground = JarvisTextPrimary,
    surface = JarvisSurface,
    onSurface = JarvisTextPrimary,
    surfaceVariant = JarvisSurfaceVariant,
    onSurfaceVariant = JarvisTextSecondary,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = JarvisSurface,
    surfaceContainerHigh = JarvisSurfaceVariant,
    surfaceContainerHighest = JarvisSurfaceHighlight,
    outline = JarvisBorderStrong,
    outlineVariant = JarvisBorder,
    error = JarvisRed,
    onError = ErrorOnDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(GlassRadius.Small),
    medium = RoundedCornerShape(GlassRadius.Button),
    large = RoundedCornerShape(GlassRadius.Card),
    extraLarge = RoundedCornerShape(GlassRadius.Pill)
)

/** Aksen primer halus untuk identitas merek. */
val AuroraGradient: Brush = Brush.linearGradient(
    listOf(JarvisCyan, JarvisCyanDark)
)

/** Permukaan kartu utama dengan pencahayaan vertikal sangat lembut. */
val HeroCardGradient: Brush = Brush.verticalGradient(
    listOf(GlassElevatedTint, GlassSurfaceTint)
)

/** Garis tepi struktural netral untuk seluruh kartu (bebas efek neon). */
val GlassBorderBrush: Brush = SolidColor(JarvisBorder)

/** Garis tepi kartu utama — halus dengan highlight atas tipis (pra-alokasi). */
val CardBorderCyanViolet: Brush = Brush.verticalGradient(
    listOf(GlassRimBright, GlassRimCatch)
)

/** Garis tepi kartu sekunder — netral konsisten (pra-alokasi). */
val CardBorderTealViolet: Brush = SolidColor(JarvisBorder)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LiquidGlassColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
