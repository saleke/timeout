package com.timeout.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.staticCompositionLocalOf

/** Compose's duration-scale context additionally honors Android's system animations-off setting. */
val LocalReducedMotion = staticCompositionLocalOf { false }
val TimeOutEaseOut = CubicBezierEasing(DesignTokens.easeOut0, DesignTokens.easeOut1, DesignTokens.easeOut2, DesignTokens.easeOut3)
