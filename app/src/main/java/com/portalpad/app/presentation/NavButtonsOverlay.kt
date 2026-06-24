package com.portalpad.app.presentation

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.portalpad.app.service.InputInjector
import kotlin.math.abs

/**
 * Draggable floating overlay with Back and Home buttons. Snaps to nearest
 * horizontal edge after a drag. Each button is a self-contained clickable
 * view with its own touch handler — guarantees clicks fire correctly even
 * if the container's drag handler swallowed something.
 *
 * Used by the trackpad "floating navigation buttons" setting. Lives on the
 * EXTERNAL display so users on AR glasses can drive Back/Home without
 * looking at the phone.
 */
class NavButtonsOverlay(
    serviceContext: Context,
    private val display: Display,
    private val injector: InputInjector,
) {
    // Mirror CursorOverlay / DockOverlay: build the OverlayHost internally.
    // The window type it picks (TYPE_ACCESSIBILITY_OVERLAY when the a11y
    // service is bound, else TYPE_APPLICATION_OVERLAY) is what keeps these
    // overlays from triggering Chrome's cross-display popup dismiss.
    private val overlayHost = OverlayHost.forDisplay(display, serviceContext)
    private val displayContext = overlayHost.context
    private val windowManager = overlayHost.windowManager
    private var view: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (view != null) return  // idempotent

        val density = displayContext.resources.displayMetrics.density
        val container = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 34f * density
                setColor("#E61A1326".toColorInt())
                setStroke((1 * density).toInt(), "#3A2D55".toColorInt())
            }
            elevation = 12f * density
            setPadding(
                (12 * density).toInt(), (8 * density).toInt(),
                (12 * density).toInt(), (8 * density).toInt(),
            )
        }
        // Two buttons only — Back and Home. Recents is omitted because the
        // system Overview UI can't render on a secondary/virtual display
        // (it'd show on the phone instead, defeating the point).
        container.addView(navButton(density, "‹", "Back") { injector.backGuarded() })
        container.addView(navButton(density, "○", "Home") { injector.home() })

        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - (240 * density).toInt()
            y = metrics.heightPixels - (160 * density).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        attachDragHandling(container, params, metrics)

        try {
            windowManager.addView(container, params)
            this.view = container
            this.params = params
            Log.d(TAG, "Nav overlay attached on display ${display.displayId}")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not attach nav overlay", t)
        }
    }

    fun dismiss() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        params = null
    }

    private fun navButton(
        density: Float,
        glyph: String,
        contentDescription: String,
        onClick: () -> Unit,
    ): TextView {
        // Each button is a TextView with its own touch handler so click
        // detection is independent of the container-level drag listener.
        // The drag-vs-click distinction lives in this per-button handler:
        // if the finger moves beyond a small slop, we forward the event to
        // the parent (which moves the window). Otherwise we fire onClick.
        return TextView(displayContext).apply {
            text = glyph
            this.contentDescription = contentDescription
            setTextColor("#FFB547".toColorInt())   // amber
            textSize = 46f                          // large enough to read/tap at glasses distance
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            val padH = (30 * density).toInt()
            val padV = (22 * density).toInt()
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = false
            background = GradientDrawable().apply {
                cornerRadius = 20f * density
                setColor(Color.TRANSPARENT)
            }

            // Per-button touch handler — fires onClick on a true tap (no
            // drag detected). On drag, returns false so the parent's drag
            // listener takes over moving the window.
            var downX = 0f
            var downY = 0f
            val slopPx = 24f * density
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        false  // let parent see DOWN too (records its baseline)
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = abs(event.rawX - downX)
                        val dy = abs(event.rawY - downY)
                        if (dx < slopPx && dy < slopPx) {
                            onClick()
                            true   // consumed — don't also trigger parent edge-snap
                        } else {
                            false  // let parent handle as drag-end / snap
                        }
                    }
                    else -> false  // pass MOVE etc. to parent for dragging
                }
            }
        }
    }

    private fun attachDragHandling(
        container: LinearLayout,
        params: WindowManager.LayoutParams,
        metrics: DisplayMetrics,
    ) {
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var moved = false
        val slopPx = 24f * displayContext.resources.displayMetrics.density

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    false   // allow children to also see DOWN
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!moved && (abs(dx) > slopPx || abs(dy) > slopPx)) moved = true
                    if (moved) {
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    moved
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val centerX = params.x + container.width / 2
                        val edge = if (centerX < metrics.widthPixels / 2) 16
                        else metrics.widthPixels - container.width - 16
                        params.x = edge
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    moved
                }
                else -> false
            }
        }
    }

    companion object { private const val TAG = "NavButtonsOverlay" }
}
