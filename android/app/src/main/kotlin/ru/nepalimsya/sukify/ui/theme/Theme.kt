package ru.nepalimsya.sukify.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val ColorScheme = darkColorScheme(
    primary = SukifyColors.Accent,
    onPrimary = Color.Black,
    secondary = SukifyColors.Text,
    onSecondary = Color.Black,
    background = SukifyColors.Elevated,
    onBackground = SukifyColors.Text,
    surface = SukifyColors.Elevated,
    onSurface = SukifyColors.Text,
    surfaceVariant = SukifyColors.CardPressed,
    onSurfaceVariant = SukifyColors.TextSubdued,
    surfaceContainer = SukifyColors.Card,
    surfaceContainerHigh = SukifyColors.CardPressed,
    surfaceContainerHighest = SukifyColors.Chip,
    surfaceContainerLow = SukifyColors.Highlight,
    outline = SukifyColors.TextMuted,
    outlineVariant = SukifyColors.Divider,
    error = SukifyColors.Error,
    scrim = Color.Black,
)

private val MaterialType = Typography(
    displayLarge = SukifyType.Hero,
    headlineMedium = SukifyType.PageTitle,
    headlineSmall = SukifyType.SectionTitle,
    titleLarge = SukifyType.Title,
    titleMedium = SukifyType.BodyBold,
    titleSmall = SukifyType.BodySmallBold,
    bodyLarge = SukifyType.Body,
    bodyMedium = SukifyType.BodySmall,
    bodySmall = SukifyType.Caption,
    labelLarge = SukifyType.BodySmallBold,
    labelMedium = SukifyType.Label,
    labelSmall = SukifyType.Tiny,
)

/** Только тёмная тема — как и в вебе. */
@Composable
fun SukifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, typography = MaterialType) {
        // Рябь приглушённая, белая: на тёмном фоне цветная выглядит грязно.
        CompositionLocalProvider(LocalIndication provides ripple(color = Color.White)) {
            content()
        }
    }
}
