package com.portalpad.app.presentation

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.AccessMode
import com.portalpad.app.service.PortalPadAccessibilityService
import com.portalpad.app.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * A top banner shown on the EXTERNAL display when the user is relying on Shizuku
 * and Shizuku's service has disconnected (installed but the binder is dead —
 * status NOT_RUNNING). It explains why input froze and what to do, since the
 * user is looking at the glasses, not the phone. Auto-hides when Shizuku
 * reconnects (status returns to READY).
 *
 * Gated on accessMode == SHIZUKU so root users (who don't depend on Shizuku)
 * never see it. NOT_FOCUSABLE — purely informational; the user acts on the
 * phone (Shizuku can't be restarted from the frozen glasses).
 *
 * Honest caveat: this overlay is drawn on the virtual display via the app's own
 * WindowManager (not via Shizuku), so it should still render even with Shizuku
 * dead — UNLESS the OEM tears down the virtual display when Shizuku dies, in
 * which case the glasses go blank and there's nothing to draw on.
 */
class ShizukuWarningOverlay(
    serviceContext: Context,
    private val display: Display,
) {
    private val overlayHost = OverlayHost.forDisplay(display, serviceContext)
    private val displayContext = overlayHost.context
    private val windowManager = overlayHost.windowManager
    private var view: View? = null
    private var chipView: View? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /** Begin observing Shizuku status; show/hide the banner accordingly. */
    fun show() {
        if (scope != null) return // idempotent
        val app = PortalPadApp.instance
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        job = s.launch {
            // One-shot guard so the experimental "soft" toast fires once per
            // disconnect transition, not on every flow emission.
            var softShown = false
            // Normal path → full cover (input really is frozen).
            // Experimental app-owned mode → the session survives, so don't block:
            // a one-shot toast + a persistent corner chip instead.
            combine(
                app.prefs.accessMode,
                app.shizuku.status,
                app.sessionAppOwned,
            ) { mode, status, appOwned ->
                val disconnected = mode == AccessMode.SHIZUKU &&
                    status == ShizukuManager.Status.NOT_RUNNING
                Pair(disconnected && !appOwned, disconnected && appOwned) // (cover, soft)
            }.collect { (showCover, showSoft) ->
                if (showCover) attachBanner() else removeBanner()

                if (showSoft) {
                    // Taps aren't actually paused anymore — they fall back to the
                    // accessibility service if it's enabled. Message depends on
                    // whether that fallback is available right now.
                    val a11yReady = PortalPadAccessibilityService.instance != null
                    if (!softShown) {
                        softShown = true
                        GlassesToast.show(
                            PortalPadApp.instance,
                            display,
                            if (a11yReady)
                                "Shizuku is off. Input now runs through the Accessibility " +
                                    "service — taps and gestures still work, but hovering " +
                                    "won't highlight. Restart Shizuku for full input."
                            else
                                "Shizuku is off. Enable PortalPad's Accessibility service to " +
                                    "keep using input on the external display, or restart Shizuku.",
                            7000L,
                            title = "Shizuku Off",
                        )
                    }
                    attachChip(
                        if (a11yReady) "⚠ Shizuku off · using accessibility"
                        else "⚠ Shizuku off · enable accessibility",
                    )
                } else {
                    softShown = false
                    removeChip()
                }
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        job = null
        scope = null
        removeBanner()
        removeChip()
    }

    private fun attachBanner() {
        if (view != null) return
        val density = displayContext.resources.displayMetrics.density

        // Size everything off the PHYSICAL display's pixel width, not sp/dp, so
        // the message is fully independent of the DPI slider (which only changes
        // the virtual display's density — this overlay lives on the physical
        // display) and of any density value at all. setTextSize(PX, …) takes raw
        // pixels. Title ≈ 4.5% of width, etc.
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        val w = metrics.widthPixels.coerceAtLeast(640)
        val iconPx = w * 0.075f
        val titlePx = w * 0.045f
        val bodyPx = w * 0.026f
        val padPx = (w * 0.035f).toInt()

        // Full-screen dark backdrop so the message is unmissable (input is frozen
        // when Shizuku dies, so covering the screen interrupts nothing the user
        // could interact with). The card is centered with large text.
        val backdrop = android.widget.FrameLayout(displayContext).apply {
            setBackgroundColor("#E6000000".toColorInt())   // ~90% black
        }

        val card = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor("#1A1326".toColorInt())
                setStroke((2f * density).toInt(), "#FFB547".toColorInt())
            }
            elevation = 24f * density
            setPadding(padPx, padPx, padPx, padPx)
        }
        card.addView(
            TextView(displayContext).apply {
                text = "⚠"
                setTextColor("#FFB547".toColorInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, iconPx)
                gravity = Gravity.CENTER
            },
        )
        card.addView(
            TextView(displayContext).apply {
                text = "Shizuku is disconnected"
                setTextColor("#FFB547".toColorInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titlePx)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, (titlePx * 0.4f).toInt(), 0, 0)
            },
        )
        card.addView(
            TextView(displayContext).apply {
                text = "Please open the Shizuku app to view connection " +
                    "status and restart the service."
                setTextColor("#F0EBF5".toColorInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bodyPx)
                gravity = Gravity.CENTER
                setPadding(0, (bodyPx * 0.8f).toInt(), 0, 0)
            },
        )

        backdrop.addView(
            card,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                val margin = (w * 0.06f).toInt()
                leftMargin = margin
                rightMargin = margin
            },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )

        try {
            windowManager.addView(backdrop, params)
            view = backdrop
            Log.d(TAG, "Shizuku warning shown on display ${display.displayId}")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not attach Shizuku warning", t)
        }
    }

    private fun removeBanner() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    /**
     * Small, dim, non-touchable corner marker shown the whole time Shizuku is
     * disconnected in experimental app-owned mode — so the reason taps are dead
     * stays on screen for someone who looks up mid-session, after the one-shot
     * toast has faded. Removed on reconnect.
     */
    private fun attachChip(chipText: String) {
        if (chipView != null) return
        val density = displayContext.resources.displayMetrics.density
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        val w = metrics.widthPixels.coerceAtLeast(640)
        val textPx = w * 0.020f
        val padHpx = (w * 0.018f).toInt()
        val padVpx = (w * 0.010f).toInt()

        val chip = TextView(displayContext).apply {
            text = chipText
            setTextColor("#FFB547".toColorInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textPx)
            gravity = Gravity.CENTER
            setPadding(padHpx, padVpx, padHpx, padVpx)
            background = GradientDrawable().apply {
                cornerRadius = 18f * density
                setColor("#CC1B1B20".toColorInt())
                setStroke((1.5f * density).toInt(), "#66FFB547".toColorInt())
            }
            elevation = 20f * density
            alpha = 0.92f
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            val m = (w * 0.02f).toInt()
            x = m; y = m
        }

        try {
            windowManager.addView(chip, params)
            chipView = chip
            Log.d(TAG, "Shizuku-off chip shown on display ${display.displayId}")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not attach Shizuku-off chip", t)
        }
    }

    private fun removeChip() {
        chipView?.let { runCatching { windowManager.removeView(it) } }
        chipView = null
    }

    companion object { private const val TAG = "ShizukuWarning" }
}
