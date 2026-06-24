package com.portalpad.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PortalPadColorScheme = darkColorScheme(
    primary = AbPrimary,
    onPrimary = AbOnSurface,
    primaryContainer = AbPrimaryDim,
    onPrimaryContainer = AbOnSurface,
    secondary = AbAccent,
    onSecondary = AbOnSurface,
    background = AbBackground,
    onBackground = AbOnSurface,
    surface = AbSurface,
    onSurface = AbOnSurface,
    surfaceVariant = AbSurfaceVariant,
    onSurfaceVariant = AbOnSurfaceMuted,
    error = AbDanger,
)

@Composable
fun PortalPadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PortalPadColorScheme,
        typography = PortalPadTypography,
        content = content,
    )
}
