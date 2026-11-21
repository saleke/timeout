package com.timeout.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class SupportingColors(val warning: Color, val onWarning: Color, val warningContainer: Color,
    val success: Color, val successContainer: Color, val onSuccess: Color)

val LocalSupportingColors = staticCompositionLocalOf {
    SupportingColors(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)
}

@Composable
fun TimeOutTheme(appearance: String = "system", content: @Composable () -> Unit) {
    val dark = when (appearance) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val c = if (dark) DesignTokens.dark else DesignTokens.light
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.getValue("primary"), onPrimary = c.getValue("onPrimary"),
        primaryContainer = c.getValue("primaryContainer"), onPrimaryContainer = c.getValue("onPrimaryContainer"),
        secondary = c.getValue("primary"), onSecondary = c.getValue("onPrimary"),
        secondaryContainer = c.getValue("primaryContainer"), onSecondaryContainer = c.getValue("onPrimaryContainer"),
        tertiary = c.getValue("primary"), onTertiary = c.getValue("onPrimary"),
        tertiaryContainer = c.getValue("primaryContainer"), onTertiaryContainer = c.getValue("onPrimaryContainer"),
        background = c.getValue("background"), onBackground = c.getValue("onSurface"),
        surface = c.getValue("surface"), onSurface = c.getValue("onSurface"),
        surfaceVariant = c.getValue("surfaceRaised"), onSurfaceVariant = c.getValue("onSurfaceVariant"),
        surfaceContainer = c.getValue("surface"), surfaceContainerLow = c.getValue("surface"),
        surfaceContainerHigh = c.getValue("surfaceRaised"), surfaceContainerHighest = c.getValue("surfaceRaised"),
        surfaceContainerLowest = c.getValue("background"), surfaceDim = c.getValue("background"),
        surfaceBright = c.getValue("surfaceRaised"),
        outline = c.getValue("outline"), outlineVariant = c.getValue("divider"),
        surfaceTint = c.getValue("primary"), scrim = c.getValue("scrim"),
        inverseSurface = c.getValue("inverseSurface"), inverseOnSurface = c.getValue("inverseOnSurface"),
        inversePrimary = c.getValue("inversePrimary"),
        error = c.getValue("error"), errorContainer = c.getValue("errorContainer"),
        onErrorContainer = c.getValue("onErrorContainer"),
        onError = if (dark) c.getValue("errorContainer") else c.getValue("onPrimary"),
    )
    val typography = Typography(
        displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
            fontSize = DesignTokens.heroTimeSize.sp, lineHeight = DesignTokens.heroTimeLine.sp, fontFeatureSettings = "tnum"),
        headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
            fontSize = DesignTokens.screenTitleSize.sp, lineHeight = DesignTokens.screenTitleLine.sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
            fontSize = 24.sp, lineHeight = 32.sp),
        titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
            fontSize = DesignTokens.sectionTitleSize.sp, lineHeight = DesignTokens.sectionTitleLine.sp),
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif,
            fontSize = DesignTokens.bodySize.sp, lineHeight = DesignTokens.bodyLine.sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif,
            fontSize = DesignTokens.supportingSize.sp, lineHeight = DesignTokens.supportingLine.sp),
        labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
            fontSize = DesignTokens.buttonLabelSize.sp, lineHeight = DesignTokens.buttonLabelLine.sp),
    )
    CompositionLocalProvider(LocalSupportingColors provides SupportingColors(
        c.getValue("warning"), c.getValue("onWarningContainer"), c.getValue("warningContainer"), c.getValue("success"),
        c.getValue("successContainer"), c.getValue("onSuccessContainer"))) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
