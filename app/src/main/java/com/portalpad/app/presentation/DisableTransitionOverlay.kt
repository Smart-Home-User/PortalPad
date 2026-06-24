package com.portalpad.app.presentation

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.toColorInt

/**
 * A full-screen black cover with a "PortalPad" label + spinner, shown on the
 * PHONE display during the Disable teardown.
 *
 * Why: when the trackpad is disabled, tearing down the trusted VirtualDisplay
 * makes the system migrate whatever app was on the glasses (e.g. Chrome) to
 * display 0 and surface it for a moment before our MainActivity reassert brings
 * PortalPad back. That caused a brief "flash of Chrome". This overlay covers
 * the phone during that window so the transition reads as a clean, intentional
 * black screen instead of a glitchy app flash.
 *
 * Hosted from the APPLICATION context (not the dying foreground service) so it
 * survives the service's onDestroy, and auto-dismisses after a fixed window
 * that comfortably covers the MainActivity reassert.
 */
object DisableTransitionOverlay {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var current: Pair<WindowManager, View>? = null
    private var dismissRunnable: Runnable? = null

    /**
     * Show the black cover on the phone (default display) for [durationMs],
     * then remove it. Safe to call from any thread. Uses TYPE_APPLICATION_OVERLAY
     * (we hold SYSTEM_ALERT_WINDOW); failures are swallowed since this is purely
     * cosmetic.
     */
    fun show(appContext: Context, durationMs: Long = 1100L, status: String? = null) {
        showInternal(appContext, durationMs, status)
    }

    /**
     * Show the cover with no real auto-dismiss — caller removes it via [dismiss]
     * when the work (e.g. trackpad enable) is ready. A safety [maxMs] still
     * removes it if the ready signal never arrives, so it can't get stuck.
     */
    fun showUntilDismissed(appContext: Context, maxMs: Long = 6000L, status: String? = null) {
        showInternal(appContext, maxMs, status)
    }

    /** Remove the cover now (e.g. once enable has finished bringing things up). */
    fun dismiss() {
        mainHandler.post { dismissNow() }
    }

    private fun showInternal(appContext: Context, durationMs: Long, status: String? = null) {
        mainHandler.post {
            dismissNow()
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = appContext.resources.displayMetrics.density

            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.BLACK)
            }
            val label = TextView(appContext).apply {
                text = "PortalPad"
                setTextColor("#F2F2F5".toColorInt())
                textSize = 32f
                gravity = Gravity.CENTER
            }
            container.addView(label)
            // Optional "what it's doing" line under the brand label.
            if (!status.isNullOrBlank()) {
                val statusView = TextView(appContext).apply {
                    text = status
                    setTextColor("#A8A8B0".toColorInt())
                    textSize = 16f
                    gravity = Gravity.CENTER
                    val top = (8 * density).toInt()
                    setPadding(0, top, 0, 0)
                }
                container.addView(statusView)
            }
            val spinner = ProgressBar(appContext).apply {
                val s = (54 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    topMargin = (24 * density).toInt()
                }
            }
            container.addView(spinner)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.CENTER
                // Extend the black behind the status/navigation bar areas so no
                // sliver of the migrating app (or system bar transitions) leaks
                // through at the top/bottom edges. Works the same in landscape.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            runCatching {
                wm.addView(container, lp)
                current = wm to container
                // Hide the system bars on this overlay so the navigation bar /
                // gesture pill doesn't animate visibly at the bottom edge during
                // the teardown (the "wavy pattern" at the bottom). We drive the
                // decor view's system-UI visibility to immersive.
                runCatching {
                    @Suppress("DEPRECATION")
                    container.systemUiVisibility =
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                }
            }.onFailure { Log.w(TAG, "DisableTransitionOverlay addView failed", it) }

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

    private const val TAG = "DisableTransition"
}
