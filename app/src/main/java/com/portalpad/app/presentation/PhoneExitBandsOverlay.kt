package com.portalpad.app.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A slim, draggable "⠿ Tap to exit" pill shown over the PHONE display (display 0)
 * while a settings window we launched is open there (settings now open fullscreen,
 * so a band in the margins is no longer possible — the pill floats above instead).
 *
 *  • Tap            → [onExit] (the owner closes the window + clears the pill).
 *  • Hold and drag  → move it out of the way of whatever's behind it.
 *
 * A small movement threshold separates a drag from a tap, so moving it never
 * triggers an accidental exit. The leading grip glyph signals it's draggable, and
 * its last position is remembered across launches. The owning service still clears
 * it if the window vanishes or a timeout fires, so it can't get orphaned.
 */
class PhoneExitBandsOverlay(
    private val serviceContext: Context,
    private val display: Display,
    @Suppress("unused") private val bandHeightPx: Int,
    private val onExit: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pill: View? = null
    private var wm: WindowManager? = null
    @Volatile var isShowing = false
        private set

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        mainHandler.post {
            if (isShowing) return@post
            val host = OverlayHost.forDisplay(display, serviceContext)
            wm = host.windowManager
            val ctx = host.context
            val density = ctx.resources.displayMetrics.density
            // Real (physical) metrics vs the window's own max bounds. Touch events
            // (rawX/rawY) for this overlay are in the window's coordinate space, so
            // we clamp against the WINDOW metrics — if those disagree with the
            // physical size, the log below shows it (that mismatch is the prime
            // suspect for "can't drag past the middle").
            val rm = android.util.DisplayMetrics().also {
                @Suppress("DEPRECATION") display.getRealMetrics(it)
            }
            val maxB = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                runCatching { host.windowManager.maximumWindowMetrics.bounds }.getOrNull()
            } else null
            val screenW = (maxB?.width() ?: rm.widthPixels).coerceAtLeast(1)
            val screenH = (maxB?.height() ?: rm.heightPixels).coerceAtLeast(1)

            val view = makePill(ctx, density)
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                host.windowType, flags, PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }

            // Always start centred on each launch (we deliberately do NOT remember a
            // dragged position — the pill re-centres every time). It can still be
            // dragged anywhere while it's showing; that just doesn't carry over.
            params.x = 0
            params.y = 0

            val slop = ViewConfiguration.get(ctx).scaledTouchSlop
            var downRawX = 0f; var downRawY = 0f
            var startX = 0; var startY = 0
            var dragging = false
            view.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = e.rawX; downRawY = e.rawY
                        startX = params.x; startY = params.y
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downRawX; val dy = e.rawY - downRawY
                        if (!dragging && hypot(dx, dy) > slop) dragging = true
                        if (dragging) {
                            val w = view.width; val h = view.height
                            params.x = (startX + dx).roundToInt()
                                .coerceIn(0, (screenW - w).coerceAtLeast(0))
                            params.y = (startY + dy).roundToInt()
                                .coerceIn(0, (screenH - h).coerceAtLeast(0))
                            runCatching { wm?.updateViewLayout(view, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (e.actionMasked == MotionEvent.ACTION_UP && !dragging) {
                            onExit()
                        }
                        true
                    }
                    else -> false
                }
            }

            runCatching {
                host.windowManager.addView(view, params)
                pill = view
                isShowing = true
                view.post {
                    val w = view.width; val h = view.height
                    params.x = ((screenW - w) / 2).coerceAtLeast(0)
                    params.y = ((screenH - h) / 2).coerceAtLeast(0)
                    runCatching { wm?.updateViewLayout(view, params) }
                }
            }.onFailure { Log.w(TAG, "exit-pill addView failed", it) }
        }
    }

    private fun makePill(ctx: Context, density: Float): View {
        val label = TextView(ctx).apply {
            text = "\u283F  Tap to exit"   // ⠿ grip dots + label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            val ph = (16f * density).roundToInt()
            val pv = (7f * density).roundToInt()
            setPadding(ph, pv, ph, pv)
            includeFontPadding = false
        }
        return FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(0xE6B00020.toInt())     // translucent red, opaque enough to read
                setStroke((1f * density).roundToInt(), 0x55FFFFFF)
            }
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            isClickable = true
        }
    }

    fun dismiss() {
        mainHandler.post {
            if (!isShowing) return@post
            pill?.let { v -> runCatching { wm?.removeView(v) } }
            pill = null
            isShowing = false
        }
    }

    companion object {
        private const val TAG = "PhoneExitBands"

        // A service-independent pill (used for app launches that can happen before
        // the foreground service is running).
        @Volatile private var standalone: PhoneExitBandsOverlay? = null
        private val standaloneHandler = Handler(Looper.getMainLooper())

        /** Show a "Tap to exit" pill on the phone (display 0) WITHOUT needing the
         *  foreground service — e.g. when launching My Glasses / Smart Connect during
         *  setup. Tapping runs [onExit] (typically: bring PortalPad back to the
         *  front). Auto-dismisses after [autoDismissMs]. */
        fun showStandalone(appContext: Context, onExit: () -> Unit, autoDismissMs: Long = 90_000L) {
            standaloneHandler.post {
                standalone?.dismiss(); standalone = null
                val dm = appContext.getSystemService(Context.DISPLAY_SERVICE)
                    as? android.hardware.display.DisplayManager ?: return@post
                val d0 = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return@post
                val pill = PhoneExitBandsOverlay(appContext.applicationContext, d0, 0) {
                    standalone?.dismiss(); standalone = null
                    onExit()
                }
                standalone = pill
                pill.show()
                standaloneHandler.postDelayed({ standalone?.dismiss(); standalone = null }, autoDismissMs)
            }
        }

        /** Clear the standalone pill if it's showing. */
        fun dismissStandalone() {
            standaloneHandler.post { standalone?.dismiss(); standalone = null }
        }
    }
}
