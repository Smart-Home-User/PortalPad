package com.portalpad.app.presentation

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt

/**
 * A brief, auto-dismissing text message shown ON the external display (glasses).
 *
 * A plain Android [android.widget.Toast] from a service context renders on the
 * phone's default display, not the glasses — and the user is looking at the
 * glasses. So we add a short-lived overlay window on the external display using
 * the same [OverlayHost] plumbing the dock/cursor use, then remove it after a
 * delay.
 *
 * Used to explain the brief trackpad freeze when tapping Search/Mic from the
 * dock (those launch a phone-side activity, which perturbs the display for a
 * moment): the message tells the user the action moved to their phone.
 */
object GlassesToast {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var current: Pair<WindowManager, View>? = null
    private var dismissRunnable: Runnable? = null

    /**
     * Show a message centered on [display] for [durationMs], replacing any
     * message already showing. Safe to call from any thread.
     *
     * If [title] is given, renders a card (bold amber title + body, rounded
     * amber-stroked box, no dim backdrop) — used for the Shizuku-off notice. If
     * not, renders the plain single-line toast (Search/Mic "moved to phone").
     * All sizing is off the physical display's pixel width (never sp/dp) so the
     * DPI slider can't change it.
     */
    fun show(
        serviceContext: Context,
        display: Display,
        message: String,
        durationMs: Long = 3500L,
        title: String? = null,
    ) {
        mainHandler.post {
            dismissNow()
            val host = OverlayHost.forDisplay(display, serviceContext)
            val ctx = host.context
            val density = ctx.resources.displayMetrics.density

            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
            val w = metrics.widthPixels.coerceAtLeast(640)

            val rootView: View
            val boxW: Int
            if (title != null) {
                // Card style: bold centered title + body, rounded amber-stroked
                // box, no backdrop. Wider (80%) so the body wraps to ~2 lines.
                boxW = (w * 0.80f).toInt()
                val titlePx = w * 0.034f
                val bodyPx = w * 0.026f
                val padPx = (w * 0.035f).toInt()
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(padPx, padPx, padPx, padPx)
                    background = GradientDrawable().apply {
                        cornerRadius = 24f * density
                        setColor("#F21A1326".toColorInt())
                        setStroke((2f * density).toInt(), "#FFB547".toColorInt())
                    }
                    elevation = 24f * density
                }
                val full = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                card.addView(
                    TextView(ctx).apply {
                        setText(title)
                        setTextColor("#FFB547".toColorInt())
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titlePx)
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        gravity = Gravity.CENTER
                    },
                    full,
                )
                card.addView(
                    TextView(ctx).apply {
                        setText(message)
                        setTextColor("#F0EBF5".toColorInt())
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bodyPx)
                        gravity = Gravity.CENTER
                        setPadding(0, (bodyPx * 0.6f).toInt(), 0, 0)
                    },
                    full,
                )
                rootView = card
            } else {
                // Plain single-line toast (unchanged).
                boxW = (w * 0.72f).toInt()
                val textPx = w * 0.032f
                val padHpx = (w * 0.045f).toInt()
                val padVpx = (w * 0.03f).toInt()
                rootView = TextView(ctx).apply {
                    setText(message)
                    setTextColor("#F2F2F5".toColorInt())
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx)
                    gravity = Gravity.CENTER
                    setPadding(padHpx, padVpx, padHpx, padVpx)
                    background = GradientDrawable().apply {
                        cornerRadius = 24f * density
                        setColor("#F21B1B20".toColorInt())
                        setStroke((2 * density).toInt(), "#40FFFFFF".toColorInt())
                    }
                    elevation = 24f * density
                }
            }

            // Fixed pixel width (not WRAP_CONTENT): on the external display a
            // wrap-content window can collapse to a sliver and stack the text
            // vertically. A definite width forces a tidy block; height stays
            // wrap-content to fit however many lines it takes.
            val lp = WindowManager.LayoutParams(
                boxW,
                WindowManager.LayoutParams.WRAP_CONTENT,
                host.windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
            }

            runCatching {
                host.windowManager.addView(rootView, lp)
                current = host.windowManager to rootView
            }.onFailure { Log.w(TAG, "GlassesToast addView failed", it) }

            val r = Runnable { dismissNow() }
            dismissRunnable = r
            mainHandler.postDelayed(r, durationMs)
        }
    }

    private fun dismissNow() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        current?.let { (wm, v) -> runCatching { wm.removeView(v) } }
        current = null
    }

    private const val TAG = "GlassesToast"
}
