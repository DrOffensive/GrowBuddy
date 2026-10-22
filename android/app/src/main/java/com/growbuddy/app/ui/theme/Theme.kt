package com.growbuddy.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.growbuddy.app.R

// Colours sampled from the GrowBuddy 0.1.6 app.
object Palette {
    val BackgroundTop = Color(0xFF182219)
    val BackgroundBottom = Color(0xFF0F140E)
    val Background = Color(0xFF131A13)
    val Card = Color(0xFF131A13)
    val CardRaised = Color(0xFF2F3B2F) // plant cards, list rows, feed frame
    val Disc = Color(0xFF283428) // VPD dial disc
    val Pill = Color(0xFF222222) // RSSI / battery pills
    val NavBar = Color(0xFF1A241C)
    val NavCenter = Color(0xFF2E4032)
    val TabBar = Color(0xFF1C261E)
    val SearchField = Color(0xFF4D634E)
    val Button = Color(0xFF233122)
    val PhotoPlaceholder = Color(0xFF707070)
    val CardBorder = Color(0xFF97C085)
    val Divider = Color(0xFF2E4030)
    val TextPrimary = Color(0xFFF2F5F1)
    val TextSecondary = Color(0xFF8CA98E)
    val Green = Color(0xFF4BAF6F) // brand (filter icon, buttons)
    val NavSelected = Color(0xFF82C091)
    val ConnectionOk = Color(0xFF16F033)
    val Danger = Color(0xFFC34342) // dial danger zones / critical
    val Warning = Color(0xFFF5A623)
    val Teal = Color(0xFF4DD0E1)

    // Aliases used across screens.
    val Alert = Danger
    val SurfaceRaised = CardRaised

    val backgroundBrush = Brush.verticalGradient(listOf(BackgroundTop, BackgroundBottom))
}

val Condensed = FontFamily(
    Font(R.font.roboto_condensed_regular, FontWeight.Normal),
    Font(R.font.roboto_condensed_medium, FontWeight.Medium),
    Font(R.font.roboto_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.roboto_condensed_bold, FontWeight.Bold),
)

val Mono = FontFamily(
    Font(R.font.roboto_mono_regular, FontWeight.Normal),
    Font(R.font.roboto_mono_medium, FontWeight.Medium),
    Font(R.font.roboto_mono_bold, FontWeight.Bold),
)

/** Numeric readouts (temperatures, pH, EC, MACs), as in 0.1.6. */
val NumericStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium)

private val ColorScheme = darkColorScheme(
    primary = Palette.Green,
    onPrimary = Palette.TextPrimary,
    primaryContainer = Palette.Button,
    onPrimaryContainer = Palette.TextPrimary,
    secondary = Palette.NavSelected,
    onSecondary = Palette.Background,
    secondaryContainer = Palette.CardRaised,
    onSecondaryContainer = Palette.TextPrimary,
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Background,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.CardRaised,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainerLowest = Palette.Background,
    surfaceContainerLow = Palette.Card,
    surfaceContainer = Palette.Card,
    surfaceContainerHigh = Palette.CardRaised,
    surfaceContainerHighest = Palette.CardRaised,
    outline = Palette.SearchField, // visible on both the background and raised cards
    outlineVariant = Palette.Divider,
    error = Palette.Danger,
    onError = Palette.TextPrimary,
)

private val AppTypography = Typography().run {
    fun TextStyle.c(weight: FontWeight = fontWeight ?: FontWeight.Normal) = copy(fontFamily = Condensed, fontWeight = weight)
    copy(
        displayLarge = displayLarge.c(), displayMedium = displayMedium.c(), displaySmall = displaySmall.c(),
        headlineLarge = headlineLarge.c(FontWeight.Medium),
        headlineMedium = headlineMedium.c(FontWeight.Medium),
        headlineSmall = headlineSmall.c(FontWeight.Medium),
        titleLarge = titleLarge.c(FontWeight.Medium).copy(fontSize = 26.sp),
        titleMedium = titleMedium.c(FontWeight.Medium).copy(fontSize = 19.sp),
        titleSmall = titleSmall.c(FontWeight.Medium),
        bodyLarge = bodyLarge.c().copy(fontSize = 18.sp),
        bodyMedium = bodyMedium.c().copy(fontSize = 16.sp),
        bodySmall = bodySmall.c().copy(fontSize = 14.sp),
        labelLarge = labelLarge.c(FontWeight.Medium).copy(fontSize = 16.sp),
        labelMedium = labelMedium.c(FontWeight.Medium),
        labelSmall = labelSmall.c(FontWeight.Medium).copy(fontSize = 12.sp, letterSpacing = 0.6.sp),
    )
}

@Composable
fun GrowBuddyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, typography = AppTypography, content = content)
}
