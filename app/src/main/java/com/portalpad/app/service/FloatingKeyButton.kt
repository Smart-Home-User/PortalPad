package com.portalpad.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * A single draggable floating button that emits a fixed key event when tapped.
 *
 * Purpose: the program keys send unused gamepad keycodes (BUTTON_1..4) meant to
 * be remapped in a third-party key-mapping app (Key Mapper, Button Mapper). But
 * those apps capture a trigger only while THEIR record screen is foreground —
 * and PortalPad can only send a key while IT is foreground. Catch-22. This
 * floating button breaks it: it overlays on top of the key-mapping app, so the
 * user can have the recorder open AND tap this button to feed it the keycode.
 *
 * Layout: the pill mirrors the in-bar program button (same bg/border/text/size),
 * with a clearly-tappable Close bar directly BELOW it (a small gap between). The
 * old corner ✕ was a tiny target — missing it sent the tap through to the app
 * behind (the window is FLAG_NOT_FOCUSABLE, hence non-touch-modal, so taps that
 * miss the pill fall through). A full-width Close bar removes that footgun.
 *
 * Tap the pill → emit the key (via [onEmit]).
 * Drag the pill → move (snaps to nearest edge on release).
 * Tap Close → dismiss (and invoke onDismiss).
 *
 * Only one floating key button exists at a time; showing a new one replaces it.
 * [show] with preservePosition = true re-renders in place (used to live-refresh
 * the look after the user edits the button's appearance).
 */
class FloatingKeyButton(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    // Last on-screen position, kept across a preservePosition refresh so editing
    // the appearance doesn't fling the pill back to its spawn spot.
    private var lastX = 40
    private var lastY = -1 // -1 = not yet positioned → use the default spawn Y
    private var onDismissCb: (() -> Unit)? = null

    val isShown: Boolean get() = view != null

    /**
     * Show the floating button, styled (and optionally sized) to match the
     * source program button.
     * @param label button text (the key's name)
     * @param labelPx text size in PX (from the source button's Compose density)
     * @param bgColorArgb pill background ARGB (the button's resolved bg, incl. alpha)
     * @param borderColorArgb border ARGB (used only if [borderWidthDp] > 0)
     * @param borderWidthDp border thickness in dp (0 = no border)
     * @param textColorArgb label color ARGB
     * @param fixedWidthPx pill width in px (0 = wrap content)
     * @param fixedHeightPx pill height in px (0 = wrap content)
     * @param preservePosition keep the current on-screen position instead of re-spawning
     * @param onEmit invoked when the pill is tapped — should send the keycode
     * @param onDismiss invoked when Close is tapped
     */
    fun show(
        label: String,
        labelPx: Float,
        bgColorArgb: Int,
        borderColorArgb: Int,
        borderWidthDp: Float,
        textColorArgb: Int,
        fixedWidthPx: Int = 0,
        fixedHeightPx: Int = 0,
        preservePosition: Boolean = false,
        onEmit: () -> Unit,
        onDismiss: () -> Unit = {},
    ) {
        if (!canDrawOverlays()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — floating key suppressed")
            return
        }
        hide() // replace any existing one (does NOT clear lastX/lastY)
        onDismissCb = onDismiss

        val density = context.resources.displayMetrics.density
        val corner = 12f * density // match the program button's RoundedCornerShape(12.dp)
        val semibold =
            if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false)
            else Typeface.create("sans-serif-medium", Typeface.NORMAL)

        // The pill — mirrors the in-bar program button.
        val pill = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = corner
                setColor(bgColorArgb)
                if (borderWidthDp > 0f) {
                    setStroke((borderWidthDp * density).coerceAtLeast(1f).toInt(), borderColorArgb)
                }
            }
            elevation = 12f * density
            if (fixedHeightPx <= 0) minimumHeight = (44 * density).toInt()
            val hp = (22 * density).toInt()
            val vp = (10 * density).toInt() // match padding(vertical = 10.dp)
            setPadding(hp, vp, hp, vp)
            isClickable = true
        }
        val labelView = TextView(context).apply {
            text = label
            setTextColor(textColorArgb)
            // Raw px (not SP) so this view's own context density can't re-scale it
            // away from the pill's px dimensions, which come from the same source.
            setTextSize(TypedValue.COMPLEX_UNIT_PX, labelPx)
            typeface = semibold // match SemiBold
            gravity = Gravity.CENTER
            maxLines = 1
        }
        pill.addView(
            labelView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        // Close bar directly below the pill — a big, easy target (vs the old
        // corner ✕). Same width as the pill, small gap above.
        val closeBtn = TextView(context).apply {
            text = "✕  Close"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, labelPx * 0.95f)
            gravity = Gravity.CENTER
            typeface = semibold
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(0xE6202024.toInt())
                setStroke((1f * density).toInt(), 0x40FFFFFF)
            }
            elevation = 12f * density
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
        }
        // The pill takes the button's exact size when provided, else wraps content.
        val pillW = if (fixedWidthPx > 0) fixedWidthPx else LinearLayout.LayoutParams.WRAP_CONTENT
        val pillH = if (fixedHeightPx > 0) fixedHeightPx else LinearLayout.LayoutParams.WRAP_CONTENT
        root.addView(pill, LinearLayout.LayoutParams(pillW, pillH))
        root.addView(
            closeBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, // = pill width
                (34 * density).toInt(),
            ).apply { topMargin = (6 * density).toInt() },
        )
        closeBtn.setOnClickListener {
            val cb = onDismissCb
            hide()
            cb?.invoke()
        }

        // Position: preserve the dragged spot on a refresh, else spawn at default.
        val defaultY = (context.resources.displayMetrics.heightPixels * 0.4f).toInt()
        if (preservePosition && lastY >= 0) {
            // keep lastX / lastY
        } else {
            lastX = 40
            lastY = defaultY
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX
            y = lastY
        }

        attachInputHandling(root, pill, lp, onEmit)

        try {
            windowManager.addView(root, lp)
            view = root
            params = lp
        } catch (t: Throwable) {
            Log.e(TAG, "Could not add floating key view", t)
        }
    }

    fun hide() {
        view?.let {
            runCatching { windowManager.removeView(it) }
                .onFailure { e -> Log.w(TAG, "removeView failed", e) }
        }
        view = null
        params = null
    }

    private fun attachInputHandling(
        root: View,
        tapTarget: View,
        lp: WindowManager.LayoutParams,
        onEmit: () -> Unit,
    ) {
        var initialX = 0
        var initialY = 0
        var startX = 0f
        var startY = 0f
        var moved = false
        val slop = 24f

        // Drag/tap on the pill: a tap (no drag) emits the key; a drag moves the
        // window. The Close bar has its own click listener.
        tapTarget.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x; initialY = lp.y
                    startX = event.rawX; startY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = (initialX + dx).toInt()
                        lp.y = (initialY + dy).toInt()
                        lastX = lp.x; lastY = lp.y
                        runCatching { windowManager.updateViewLayout(root, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onEmit() else snapToEdge(root, lp)
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(root: View, lp: WindowManager.LayoutParams) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val w = root.width
        val centerX = lp.x + w / 2
        lp.x = if (centerX < screenWidth / 2) 16 else screenWidth - w - 16
        lastX = lp.x; lastY = lp.y
        runCatching { windowManager.updateViewLayout(root, lp) }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
        else true

    companion object {
        private const val TAG = "FloatingKeyButton"
    }
}
