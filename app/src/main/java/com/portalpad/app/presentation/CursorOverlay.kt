package com.portalpad.app.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.toColorInt
import com.portalpad.app.service.InputInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Renders a custom mouse cursor as a system overlay on the [display].
 *
 * Architecture (modeled on a reference cursor overlay):
 *
 *  - The overlay window is TINY (32dp x 32dp by default) — only as big as
 *    the cursor icon itself. Anchored TOP|START so its position IS its
 *    top-left corner.
 *
 *  - On every cursor move, we update LayoutParams.x/y and call
 *    WindowManager.updateViewLayout. The window physically MOVES across
 *    the display; the View's content NEVER re-invalidates on motion.
 *
 * Why this matters: a previous implementation used a fullscreen overlay
 * window and invalidated the View on every cursor move. That triggered
 * Chrome's PopupWindow (address-bar suggestions, autocomplete, search
 * dropdowns) to dismiss the moment the cursor entered them — Chrome reads
 * continuous fullscreen-overlay redraws as "screen interaction" and
 * collapses anchor popups defensively. the reference implementation side-steps the problem by
 * keeping the overlay small and moving the window itself rather than
 * redrawing fullscreen content. We mirror that exactly.
 *
 * Flags match a known-good set (792):
 *   FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_NO_LIMITS |
 *   FLAG_HARDWARE_ACCELERATED
 */
class CursorOverlay(
    private val serviceContext: Context,
    private val display: Display,
    private val injector: InputInjector,
) {

    private val overlayHost = OverlayHost.forDisplay(display, serviceContext)
    private val displayContext = overlayHost.context
    private val windowManager = overlayHost.windowManager

    private var cursorView: CursorView? = null
    private var params: WindowManager.LayoutParams? = null
    private var sizePx: Int = 0
    private var scope: CoroutineScope? = null
    private var watcher: Job? = null
    private var choreographer: Choreographer? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    private var lastAppliedX: Float = Float.NaN
    private var lastAppliedY: Float = Float.NaN
    // Per-frame ease fraction (1f = snap/off, the default). Driven live from the
    // "Cursor smoothing" pref s as (1 - s): higher smoothing → lower alpha → more
    // glide. @Volatile since the pref collector and doFrame both touch it.
    @Volatile private var easeAlpha: Float = 1f

    fun show() {
        if (cursorView != null) return

        // Cursor window size. 32dp gives us a comfortable canvas for the
        // arrow shape with a small shadow halo. the reference implementation uses 18dp; we go
        // slightly larger because our arrow shape is taller.
        val dm = displayContext.resources.displayMetrics
        sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, dm)
            .toInt().coerceAtLeast(16)

        val view = CursorView(displayContext)
        val p = WindowManager.LayoutParams(
            sizePx, sizePx,
            // Window type — TYPE_ACCESSIBILITY_OVERLAY (2032) when an
            // accessibility service is bound (a reference approach); falls
            // back to TYPE_APPLICATION_OVERLAY (2038). The accessibility
            // overlay type is critical for Chrome's address-bar dropdown
            // to survive cursor motion on Samsung One UI.
            overlayHost.windowType,
            // a known-good cursor overlay flag set, confirmed against
            // a reference accessibility service's cursor attach:
            //
            //   new LayoutParams(width, height, type, FLAGS=0x318, FORMAT=-3)
            //
            //   0x318 = 0x008 + 0x010 + 0x100 + 0x200
            //         = FLAG_NOT_FOCUSABLE        — no IME focus
            //         | FLAG_NOT_TOUCHABLE        — touches pass through
            //         | FLAG_LAYOUT_NO_LIMITS     — extends beyond display
            //         | FLAG_LAYOUT_IN_SCREEN     — full-display coords
            //
            // Earlier we used FLAG_HARDWARE_ACCELERATED instead of
            // LAYOUT_IN_SCREEN — the comment said "= 792" but the actual
            // value was different (HW_ACCEL is 0x1000000, ours was
            // 0x1000118 = 16777496). The Chrome dropdown bug is still open
            // as of v0.17.40; matching the known-good 0x318 is the next
            // thing to test.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Top-left anchor so x/y are absolute display coordinates.
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // SOFT_INPUT_ADJUST_PAN — confirmed from a logcat trace.
            // Their cursor overlay's WindowManager.LayoutParams shows
            // `sim={adjust=pan}`. Without an explicit softInputMode, our
            // overlay defaults to ADJUST_UNSPECIFIED, and when the overlay
            // relayouts on every cursor move the system re-runs the
            // display's IME-inset layout pass. That re-evaluation is what
            // dismisses Chrome's address-bar dropdown (it's anchored to the
            // IME-control relationship). Declaring ADJUST_PAN tells the
            // window manager this window pans rather than resizes for the
            // IME, so relayouts don't trigger a display-wide IME re-layout
            // — and Chrome's dropdown survives cursor motion. This matches
            // the dumpsys diff: the reference implementation's IME insets stay stable on the VD,
            // ours were churning.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        try {
            windowManager.addView(view, p)
            cursorView = view
            params = p
            Log.d(TAG, "Cursor overlay added on display ${display.displayId} (size=${sizePx}px type=${overlayHost.windowType} a11y=${overlayHost.isAccessibilityOverlay})")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to add cursor overlay", t)
            return
        }

        // Observe cursor position. Each change moves the WINDOW via
        // updateViewLayout — the view's content does not invalidate on
        // motion, which is the whole point.
        val s = CoroutineScope(Dispatchers.Main)
        scope = s
        // Drive the cursor window move from the display's vsync via Choreographer,
        // sampling the LATEST position once per frame — instead of updateViewLayout
        // per StateFlow emission. The gesture thread updates the position StateFlow
        // rapidly; StateFlow conflates, so per-emission moves on the Main thread
        // sample the path coarsely and off-vsync (the visible "skip", worse on the
        // wide 3840 canvas where swipes are faster). One move per frame, freshest
        // position, refresh-aligned = smooth. Hover/clicks/gestures are unaffected.
        s.launch {
            val ch = Choreographer.getInstance()
            val cb = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (scope == null) return  // dismissed mid-frame
                    val (tx, ty) = injector.cursorPosition.value
                    when {
                        lastAppliedX.isNaN() -> {
                            // First frame — snap, never glide in from (0,0).
                            lastAppliedX = tx; lastAppliedY = ty
                            moveTo(tx, ty)
                        }
                        tx != lastAppliedX || ty != lastAppliedY -> {
                            val dx = tx - lastAppliedX
                            val dy = ty - lastAppliedY
                            if (easeAlpha >= 1f || dx * dx + dy * dy >= SNAP_DIST_PX * SNAP_DIST_PX) {
                                // Smoothing off (default), or a teleport/recenter — snap.
                                lastAppliedX = tx; lastAppliedY = ty
                            } else {
                                // Ease toward the latest position so a late/dropped
                                // delivery frame glides to catch up instead of jumping.
                                // Settle exactly once within 0.5px so it doesn't tick
                                // forever.
                                val nx = lastAppliedX + dx * easeAlpha
                                val ny = lastAppliedY + dy * easeAlpha
                                lastAppliedX = if (kotlin.math.abs(tx - nx) < 0.5f) tx else nx
                                lastAppliedY = if (kotlin.math.abs(ty - ny) < 0.5f) ty else ny
                            }
                            moveTo(lastAppliedX, lastAppliedY)
                        }
                    }
                    ch.postFrameCallback(this)
                }
            }
            choreographer = ch
            frameCallback = cb
            ch.postFrameCallback(cb)
        }
        s.launch {
            injector.cursorVisible.collect { visible ->
                view.setVisible(visible)
            }
        }
        // Live "Cursor smoothing" pref (s): higher = smoother. alpha = 1 - s,
        // floored at 0.1 so it never fully stalls; default 0 → alpha 1 (snap/off,
        // no added latency).
        s.launch {
            com.portalpad.app.PortalPadApp.instance.prefs
                .float(com.portalpad.app.data.PreferencesRepository.Keys.CURSOR_SMOOTHING, 0f)
                .collect { sVal -> easeAlpha = (1f - sVal).coerceIn(0.1f, 1f) }
        }
    }

    /**
     * Bring the cursor window back to the TOP of the overlay Z-order.
     *
     * Same-type overlay windows (all our overlays share one window type) stack
     * in insertion order, so any overlay added AFTER the cursor (dock, taskbar,
     * top window bar, search panel) ends up drawn on top of it — the cursor
     * then appears "behind" them. Re-adding the cursor view re-inserts it last,
     * lifting it above everything. Call this whenever a new overlay is attached
     * on this display. The cursor window is NOT_TOUCHABLE so raising it never
     * intercepts taps — it only fixes which layer the arrow is drawn on.
     */
    fun raise() {
        val v = cursorView ?: return
        val p = params ?: return
        runCatching {
            windowManager.removeViewImmediate(v)
            windowManager.addView(v, p)
            Log.d(TAG, "Cursor raised to front on display ${display.displayId}")
        }.onFailure { Log.w(TAG, "raise() failed", it) }
    }

    /**
     * Diagnostic kill switch — when false, the cursor overlay window's
     * position is never updated. The cursor remains visually frozen at
     * its initial position. All other code paths (hover events, clicks,
     * gesture recognizer) work normally.
     *
     * v0.17.34 set this false to isolate whether rapid updateViewLayout
     * calls were the dropdown-dismiss cause. Result was inconclusive
     * (dropdown still dismissed). The real cause was identified via
     * an adb dump comparison: PortalPad put overlays on the
     * PHYSICAL glasses display while Chrome ran on the VD — a cross-
     * display split that breaks Chrome's PopupWindow. the reference implementation keeps
     * everything on the VD. v0.17.35 fixes the architecture so overlays
     * live on the VD; this switch can stay true.
     *
     *   true  — cursor follows finger as usual (default for production)
     *   false — cursor window position frozen at startup
     */
    private val UPDATE_CURSOR_WINDOW = true

    /**
     * Moves the overlay window so the cursor's tip (drawn at the view's
     * top-left) sits at display coordinates ([x], [y]).
     */
    private fun moveTo(x: Float, y: Float) {
        if (!UPDATE_CURSOR_WINDOW) {
            // Diagnostic: hard-disable cursor window position updates so
            // we can test whether rapid updateViewLayout calls on the
            // physical-display overlay window are interfering with
            // PopupWindow lifecycle on the VD.
            return
        }
        val view = cursorView ?: return
        val p = params ?: return
        if (!view.isAttachedToWindow) return
        // The cursor's "hotspot" (the pointer tip) is at the view's (0, 0),
        // so the window's top-left IS the display coordinate. No offset.
        p.x = x.toInt()
        p.y = y.toInt()
        try {
            windowManager.updateViewLayout(view, p)
        } catch (_: IllegalArgumentException) {
            // View was concurrently removed; ignore.
        }
    }

    fun dismiss() {
        watcher?.cancel(); watcher = null
        frameCallback?.let { cb -> choreographer?.removeFrameCallback(cb) }
        frameCallback = null
        choreographer = null
        scope?.cancel(); scope = null
        cursorView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        cursorView = null
        params = null
    }

    /**
     * Custom view that paints the cursor at its own local (0, 0). Because
     * the window itself is positioned via LayoutParams.x/y, the view only
     * needs to draw the cursor shape relative to its top-left corner. It
     * never repaints on motion — only on visibility change.
     */
    private class CursorView(context: Context) : View(context) {
        private var visible: Boolean = true

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#FFFFFFFF".toColorInt()
            style = Paint.Style.FILL
        }
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#FF000000".toColorInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            strokeJoin = Paint.Join.ROUND
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#33000000".toColorInt()
            style = Paint.Style.FILL
        }

        private val arrowPath = Path()
        private val shadowPath = Path()

        fun setVisible(v: Boolean) {
            if (visible == v) return
            visible = v
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!visible) return
            // Draw the arrow with its tip at the view's top-left (0, 0).
            // The window position places this corner at the desired display
            // coordinate. No invalidate() needed for cursor moves — only on
            // visibility change.
            buildArrowPath(arrowPath, 0f, 0f)
            buildArrowPath(shadowPath, 1.5f, 1.5f)
            canvas.drawPath(shadowPath, shadowPaint)
            canvas.drawPath(arrowPath, fillPaint)
            canvas.drawPath(arrowPath, outlinePaint)
        }

        private fun buildArrowPath(path: Path, x: Float, y: Float) {
            path.reset()
            path.moveTo(x, y)                  // tip
            path.lineTo(x, y + 24f)             // down the left edge
            path.lineTo(x + 6f, y + 19f)        // jog right toward the notch
            path.lineTo(x + 10f, y + 28f)       // tail down-right
            path.lineTo(x + 13f, y + 26.5f)     // tail bottom
            path.lineTo(x + 9f, y + 17.5f)      // back up
            path.lineTo(x + 16f, y + 17.5f)     // top of notch
            path.close()                        // back to tip
        }
    }

    companion object {
        private const val TAG = "CursorOverlay"
        // Jumps ≥ this snap regardless of smoothing (teleport / recenter) so the
        // cursor never glides across the screen.
        private const val SNAP_DIST_PX = 500f
    }
}
