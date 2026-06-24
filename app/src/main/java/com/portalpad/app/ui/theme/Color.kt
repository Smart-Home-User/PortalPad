package com.portalpad.app.ui.theme

import androidx.compose.ui.graphics.Color

// PortalPad's palette: warm-dark base with vibrant violet primary and amber accent.
// Distinct from the reference implementation (which is blue-dark with teal primary). Names keep the
// "Ab" prefix purely as a stable internal identifier — no relation to color hue.

val AbBackground = Color(0xFF0F0A1A)        // very dark warm purple-black
val AbSurface = Color(0xFF1A1326)           // surface (cards, top bar)
val AbSurfaceVariant = Color(0xFF1F1830)    // alt surface
val AbSurfaceElevated = Color(0xFF2A2140)   // elevated tiles, buttons

val AbPrimary = Color(0xFF9B5BFF)           // vibrant violet
val AbPrimaryBright = Color(0xFFC8A6FF)     // lighter violet (highlights)
val AbPrimaryDim = Color(0xFF5B3CAA)        // darker violet

val AbAccent = Color(0xFFFFB547)            // warm amber — complementary
val AbAccentSoft = Color(0xFFFFCB7A)        // soft amber

val AbOnSurface = Color(0xFFF0EBF5)         // primary text
val AbOnSurfaceMuted = Color(0xFFA89BB8)    // secondary text
val AbOnSurfaceDim = Color(0xFF6C5F7F)      // tertiary text / inactive

val AbGridLine = Color(0xFF221A2F)          // subtle grid lines on trackpad
val AbDanger = Color(0xFFFF6B6B)            // warm red
val AbMicTint = Color(0xFF38305E)           // muted violet — mic button at rest (voice)
val AbClearTint = Color(0xFF3D2632)         // muted red — clear button at rest (destructive)
val AbClearIcon = Color(0xFFE89A94)         // soft red — trash glyph
val AbWarning = Color(0xFFE6B85C)           // soft amber — caveats / "heads up" notes
val AbSuccess = Color(0xFF5EE89F)           // cool mint green
val AbTeal = Color(0xFF34C79E)              // cool teal — Quick Wheel activity slots (deeper entry point)
val AbTabActive = AbPrimary
val AbTabInactive = AbOnSurfaceDim
