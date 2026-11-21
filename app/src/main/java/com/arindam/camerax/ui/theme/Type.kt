package com.arindam.camerax.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/**
 * Util: system type. The camera UI intentionally uses the platform font — no downloadable
 * Google Fonts provider, so window inflation never waits on Play Services font resolution.
 */
val CameraFontFamily = FontFamily.SansSerif

/** HUD chips, timers, zoom, and other metadata. */
val CameraMono = FontFamily.Monospace

private val materialDefaults = Typography()

val CameraTypography = Typography(
    displayLarge = materialDefaults.displayLarge.copy(fontFamily = CameraFontFamily),
    displayMedium = materialDefaults.displayMedium.copy(fontFamily = CameraFontFamily),
    displaySmall = materialDefaults.displaySmall.copy(fontFamily = CameraFontFamily),
    headlineLarge = materialDefaults.headlineLarge.copy(fontFamily = CameraFontFamily),
    headlineMedium = materialDefaults.headlineMedium.copy(fontFamily = CameraFontFamily),
    headlineSmall = materialDefaults.headlineSmall.copy(fontFamily = CameraFontFamily),
    titleLarge = materialDefaults.titleLarge.copy(fontFamily = CameraFontFamily),
    titleMedium = materialDefaults.titleMedium.copy(fontFamily = CameraFontFamily),
    titleSmall = materialDefaults.titleSmall.copy(fontFamily = CameraFontFamily),
    bodyLarge = materialDefaults.bodyLarge.copy(fontFamily = CameraFontFamily),
    bodyMedium = materialDefaults.bodyMedium.copy(fontFamily = CameraFontFamily),
    bodySmall = materialDefaults.bodySmall.copy(fontFamily = CameraFontFamily),
    labelLarge = materialDefaults.labelLarge.copy(fontFamily = CameraFontFamily),
    labelMedium = materialDefaults.labelMedium.copy(fontFamily = CameraFontFamily),
    labelSmall = materialDefaults.labelSmall.copy(fontFamily = CameraFontFamily)
)
