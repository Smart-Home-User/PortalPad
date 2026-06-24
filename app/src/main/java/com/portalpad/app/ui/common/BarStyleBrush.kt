package com.portalpad.app.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import com.portalpad.app.data.BarGradientType
import com.portalpad.app.data.BarStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The SINGLE place a [BarStyle] becomes a Compose [Brush]. Used by the dock, the
 * folder window, and the dock-derived panels (quick settings / notifications),
 * plus the customization live preview — so every surface honors the gradient
 * type, the LINEAR angle, and the color-balance midpoint identically, and any
 * future geometry only has to be added here once.
 *
 * (The top window bar is painted by an Android GradientDrawable, not Compose, so
 * it builds from [BarStyle.drawableOrientation] / [BarStyle.drawableColors]
 * instead — but both paths read the same fields, so the surfaces stay in sync.)
 *
 * @param opaque force both stops fully opaque (used by the dock edit-menu theme,
 *   whose background must not let content bleed through a translucent dock).
 */
fun toComposeBrush(style: BarStyle, opaque: Boolean = false, fillAlpha: Float? = null): Brush {
    var a = Color(style.colorAInt())
    var b = Color(style.colorBInt())
    if (opaque) {
        a = a.copy(alpha = 1f)
        b = b.copy(alpha = 1f)
    }
    // Bake an explicit fill alpha into both gradient stops (used by the open/
    // minimized bars to render a touch more transparent than the dock). Applied
    // after `opaque` so an explicit alpha wins if both are somehow passed.
    if (fillAlpha != null) {
        a = a.copy(alpha = fillAlpha)
        b = b.copy(alpha = fillAlpha)
    }

    // Two colors with a movable midpoint: placing the 50% mix at `mid` biases
    // where the transition sits. At mid = 0.5 that point lies on the straight
    // A→B line, so it's identical to a plain two-stop gradient (default kept).
    val mid = style.midpointFrac()
    val stops: Array<Pair<Float, Color>> =
        if (style.isEvenBalance()) arrayOf(0f to a, 1f to b)
        else arrayOf(0f to a, mid to lerp(a, b, 0.5f), 1f to b)

    return when (style.gradientType) {
        BarGradientType.LINEAR -> AngledLinearGradient(stops, style.angleDeg)
        BarGradientType.RADIAL ->
            FractionalRadialGradient(stops, style.centerXFrac(), style.centerYFrac(), style.radiusFrac())
        BarGradientType.CONICAL ->
            FractionalSweepGradient(stops, style.centerXFrac(), style.centerYFrac())
    }
}

/**
 * A size-aware linear gradient at an arbitrary [angleDeg] (clockwise, 0 =
 * top→bottom). Compose's built-in linear gradient can't take a size-relative
 * angle, so the start/end endpoints are computed across the actual draw bounds
 * at shader-creation time.
 */
private class AngledLinearGradient(
    private val stops: Array<Pair<Float, Color>>,
    angleDeg: Int,
) : ShaderBrush() {
    // Direction (start→end): 0° = down (0,1), 90° = right (1,0), etc.
    private val rad = Math.toRadians((((angleDeg % 360) + 360) % 360).toDouble())
    private val dx = sin(rad).toFloat()
    private val dy = cos(rad).toFloat()

    override fun createShader(size: Size): Shader {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // Half-length so the axis spans the box for any direction.
        val half = (abs(dx) * size.width + abs(dy) * size.height) / 2f
        val start = Offset(cx - dx * half, cy - dy * half)
        val end = Offset(cx + dx * half, cy + dy * half)
        return LinearGradientShader(
            from = start,
            to = end,
            colors = stops.map { it.second },
            colorStops = stops.map { it.first },
            tileMode = TileMode.Clamp,
        )
    }
}

/**
 * Radial gradient with a fractional [cxFrac]/[cyFrac] center and a [radiusFrac]
 * radius expressed as a fraction of the box's short side, resolved against the
 * actual draw size. cx=cy=0.5, radiusFrac=0.5 reproduces Compose's default
 * centered radial.
 */
private class FractionalRadialGradient(
    private val stops: Array<Pair<Float, Color>>,
    private val cxFrac: Float,
    private val cyFrac: Float,
    private val radiusFrac: Float,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader = RadialGradientShader(
        center = Offset(size.width * cxFrac, size.height * cyFrac),
        radius = (min(size.width, size.height) * radiusFrac).coerceAtLeast(0.01f),
        colors = stops.map { it.second },
        colorStops = stops.map { it.first },
        tileMode = TileMode.Clamp,
    )
}

/**
 * Sweep (conical) gradient with a fractional [cxFrac]/[cyFrac] center, resolved
 * against the actual draw size. cx=cy=0.5 reproduces Compose's default centered
 * sweep. (Start-angle rotation is intentionally not exposed — neither Compose
 * nor GradientDrawable supports it cleanly.)
 */
private class FractionalSweepGradient(
    private val stops: Array<Pair<Float, Color>>,
    private val cxFrac: Float,
    private val cyFrac: Float,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader = SweepGradientShader(
        center = Offset(size.width * cxFrac, size.height * cyFrac),
        colors = stops.map { it.second },
        colorStops = stops.map { it.first },
    )
}
