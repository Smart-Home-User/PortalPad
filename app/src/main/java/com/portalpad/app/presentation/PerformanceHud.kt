package com.portalpad.app.presentation

import android.graphics.Typeface
import android.content.Context
import android.view.WindowManager
import android.graphics.drawable.GradientDrawable
import android.view.Display
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.PreferencesRepository.Keys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch

/**
 * Diagnostic "Performance overlay" rendered ON the external display, as a small
 * non-interactive TextView pinned to a corner of the VD-mirror host. It reads
 * the always-on render telemetry exposed by [GlColorRenderer] (frameCount /
 * lastDrawNanos) plus the live display mode, and refreshes ~1/s.
 *
 * It is added as a sibling on top of the mirror's SurfaceView — which is NOT
 * z-ordered on top, so plain Views composite above it. The host window is
 * NOT_TOUCHABLE/NOT_FOCUSABLE, so the HUD never steals input.
 *
 * All settings (on/off, corner, which rows) are observed live from prefs, so
 * changes apply to the running display without a reconnect.
 *
 * Limitation: delivered-fps is only meaningful on the GL pipeline (the telemetry
 * is updated in GlColorRenderer.drawFrame). On the direct-surface fallback path
 * the fps row reads 0.
 */
class PerformanceHud(
    private val parent: FrameLayout,
    private val display: Display,
    // When false (system-mirror mode: no GL pipeline), the GL-sourced rows
    // (fps, draw time, "behind") are meaningless, so only the resolution/Hz
    // row — read straight from the Display — is shown.
    private val glActive: Boolean = true,
    // Optional delivered-fps source for system-mirror mode (SurfaceFlinger
    // frame stats). Returns a non-positive value when unavailable.
    private val fpsSampler: (() -> Float)? = null,
) {
    private val ctx = parent.context
    private val prefs = PortalPadApp.instance.prefs

    private var view: TextView? = null
    private var scope: CoroutineScope? = null

    @Volatile private var enabled = false
    @Volatile private var corner = CORNER_TOP_RIGHT
    @Volatile private var showFps = true
    @Volatile private var showFrameTime = true
    @Volatile private var showMode = true
    @Volatile private var showDropped = true

    // fps derivation: delta of frameCount over wall-clock between ticks.
    private var lastCount = 0L
    private var lastTickNs = 0L

    // Standalone mode (system mirror): the HUD owns its overlay window instead
    // of parenting into the VD mirror's host. Set by [standalone]; removed in
    // [stop].
    private var ownWindowManager: android.view.WindowManager? = null
    private var ownContainer: FrameLayout? = null

    fun start() {
        if (scope != null) return
        val tv = TextView(ctx).apply {
            setTextColor(0xFFEAEAF0.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx)
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            setLineSpacing(textPx * 0.18f, 1f)
            val padX = (textPx * 0.7f).toInt()
            val padY = (textPx * 0.45f).toInt()
            setPadding(padX, padY, padX, padY)
            background = GradientDrawable().apply {
                cornerRadius = textPx * 0.6f
                setColor(0xCC101018.toInt()) // translucent dark pill
            }
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        parent.addView(tv, cornerLayoutParams(corner))
        view = tv

        val s = CoroutineScope(Dispatchers.Main)
        scope = s
        s.launch { prefs.bool(Keys.HUD_ENABLED, false).collect { enabled = it; applyVisibility() } }
        s.launch { prefs.string(Keys.HUD_CORNER, CORNER_TOP_RIGHT).collect { corner = it; applyCorner() } }
        s.launch { prefs.bool(Keys.HUD_SHOW_FPS, true).collect { showFps = it } }
        s.launch { prefs.bool(Keys.HUD_SHOW_FRAMETIME, true).collect { showFrameTime = it } }
        s.launch { prefs.bool(Keys.HUD_SHOW_MODE, true).collect { showMode = it } }
        s.launch { prefs.bool(Keys.HUD_SHOW_DROPPED, true).collect { showDropped = it } }
        s.launch {
            while (true) {
                if (enabled) updateText()
                delay(1000)
            }
        }
    }

    fun stop() {
        scope?.cancel(); scope = null
        view?.let { runCatching { parent.removeView(it) } }
        view = null
        // Standalone mode: also remove our own overlay window.
        ownContainer?.let { c -> runCatching { ownWindowManager?.removeView(c) } }
        ownContainer = null
        ownWindowManager = null
    }

    private fun applyVisibility() {
        view?.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            // Seed the fps delta so the first sample isn't a huge spike.
            lastCount = GlColorRenderer.frameCount
            lastTickNs = System.nanoTime()
            updateText()
        }
    }

    private fun applyCorner() {
        val v = view ?: return
        v.layoutParams = cornerLayoutParams(corner)
        v.requestLayout()
    }

    private fun updateText() {
        val v = view ?: return
        val nowNs = System.nanoTime()
        val count = GlColorRenderer.frameCount
        val dtNs = (nowNs - lastTickNs).coerceAtLeast(1L)
        val fps = (count - lastCount) * 1e9 / dtNs
        lastCount = count
        lastTickNs = nowNs

        val drawMs = GlColorRenderer.lastDrawNanos / 1e6
        val mode = display.mode
        val hz = mode.refreshRate
        val dropped = (hz - fps).coerceAtLeast(0.0).let { Math.round(it) }

        val sb = StringBuilder()
        if (showMode) {
            sb.append("${mode.physicalWidth}×${mode.physicalHeight} · ${"%.0f".format(hz)} Hz\n")
        }
        // fps / draw / behind are derived from the GL renderer telemetry, which
        // only exists on the overlay path. In system-mirror mode there's no GL
        // pipeline, so these are suppressed rather than shown as a false 0.
        if (glActive) {
            if (showFps) sb.append("${"%.0f".format(fps)} fps\n")
            if (showFrameTime) sb.append("draw ${"%.1f".format(drawMs)} ms\n")
            if (showDropped) sb.append("vsync ${"%.0f".format(hz)} · ~$dropped behind\n")
        } else {
            // System-mirror mode: no GL loop to count, but SurfaceFlinger can
            // report delivered frames for the display. Show the same rows overlay
            // mode would (fps, and the vsync/behind row derived as hz − fps) so
            // the overlay reaches parity. The GL draw-time row is the only one
            // with no equivalent here (nothing is GL-drawn), so it stays
            // overlay-only rather than showing a meaningless value.
            val sfFps = fpsSampler?.invoke() ?: -1f
            if (sfFps > 0f) {
                if (showFps) sb.append("${"%.0f".format(sfFps)} fps\n")
                if (showDropped) {
                    val sfDropped = (hz - sfFps).coerceAtLeast(0f).let { Math.round(it) }
                    sb.append("vsync ${"%.0f".format(hz)} · ~$sfDropped behind\n")
                }
            }
        }
        v.text = sb.toString().trimEnd('\n')
    }

    private fun cornerLayoutParams(corner: String): FrameLayout.LayoutParams {
        val g = when (corner) {
            CORNER_TOP_LEFT -> Gravity.TOP or Gravity.START
            CORNER_BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            CORNER_BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.TOP or Gravity.END
        }
        return FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = g
            val m = (basePx * 0.025f).toInt()
            setMargins(m, m, m, m)
        }
    }

    // Panel pixel height is stable; the external display's reported *density* is not
    // (the XREAL display context returns ~0/garbage dpi, which collapsed sp/dp sizing),
    // so size the HUD off the panel height in raw pixels instead.
    private val basePx: Int by lazy {
        android.util.DisplayMetrics().also { display.getRealMetrics(it) }.heightPixels.coerceAtLeast(720)
    }
    private val textPx: Float by lazy { basePx * 0.028f }

    companion object {
        const val CORNER_TOP_LEFT = "top_left"
        const val CORNER_TOP_RIGHT = "top_right"
        const val CORNER_BOTTOM_LEFT = "bottom_left"
        const val CORNER_BOTTOM_RIGHT = "bottom_right"

        /** Map a corner constant to a window Gravity for the standalone window. */
        private fun windowGravity(corner: String): Int = when (corner) {
            CORNER_TOP_LEFT -> Gravity.TOP or Gravity.START
            CORNER_BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            CORNER_BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.TOP or Gravity.END
        }

        /**
         * Build a HUD that owns its own overlay window on [display] (used in
         * system-mirror mode, where there's no VD-mirror host to parent into).
         * The window is WRAP_CONTENT pinned to the configured corner — NOT
         * full-display — so it obscures only a tiny corner region and can't trip
         * Android's untrusted-touch occlusion (which would drop injected scroll
         * touches across the whole display). Non-touchable / non-focusable.
         * [fpsSampler], if provided, returns a delivered-fps to display (from
         * SurfaceFlinger); glActive is false here so GL rows are suppressed.
         */
        fun standalone(
            serviceContext: Context,
            display: Display,
            glActive: Boolean,
            fpsSampler: (() -> Float)? = null,
        ): PerformanceHud? {
            return runCatching {
                val corner = runCatching {
                    kotlinx.coroutines.runBlocking {
                        com.portalpad.app.PortalPadApp.instance.prefs
                            .string(com.portalpad.app.data.PreferencesRepository.Keys.HUD_CORNER, CORNER_TOP_RIGHT)
                            .first()
                    }
                }.getOrDefault(CORNER_TOP_RIGHT)
                val host = OverlayHost.forDisplay(display, serviceContext)
                val container = FrameLayout(host.context)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    host.windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = windowGravity(corner)
                    x = 12; y = 12
                }
                host.windowManager.addView(container, lp)
                PerformanceHud(container, display, glActive, fpsSampler).also {
                    it.ownWindowManager = host.windowManager
                    it.ownContainer = container
                    it.start()
                }
            }.getOrNull()
        }
    }
}
